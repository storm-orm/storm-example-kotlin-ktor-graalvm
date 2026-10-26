package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.SwipeWay
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.service.MatchService
import st.orm.demo.imdb.service.Opponents
import st.orm.demo.imdb.service.SwipeService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Duration
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class SwipeServiceTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun service(orm: ORMTemplate): SwipeService {
        val recommendations = recommendationService(orm)
        val matches = MatchService(
            orm.repository<GameMatchRepository>(), orm.repository<GamePlayerRepository>(), orm.repository<FriendshipRepository>(),
            orm.repository<ClubRepository>(), orm.repository<ClubMemberRepository>(), orm.repository<UserRepository>()
        )
        return SwipeService(
            matches, orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
            orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
            WatchlistService(orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository<CheckInRepository>(), recommendations),
            movieSeenService(orm),
            recommendations
        )
    }

    @Test
    fun `a swipe does what the movie page's buttons do, and the deck deals a movie once`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-swipe", name = "Alice")
        assertEquals(6, runBlocking { service.deckOf(alice) }.cards.size)

        runBlocking {
            service.swipe(alice, "tt0133093", SwipeWay.WANT, now)
            service.swipe(alice, "tt0234215", SwipeWay.PASS, now)
            service.swipe(alice, "tt0111161", SwipeWay.SEEN, now)
        }
        val movies = orm.repository<MovieRepository>()
        assertTrue(orm.repository<WatchlistRepository>().contains(alice, movies.getById("tt0133093")))
        assertTrue(orm.repository<RecommendationDismissalRepository>().contains(alice, movies.getById("tt0234215")))
        assertTrue(orm.repository<MovieSeenRepository>().contains(alice, movies.getById("tt0111161")))
        // Watched some time ago: no check-in.
        assertTrue(orm.repository<CheckInRepository>().findAll(alice).isEmpty())

        val deck = runBlocking { service.deckOf(alice) }
        assertEquals(setOf("tt0110912", "tt0068646", "tt0211915"), deck.cards.map { it.movie.id }.toSet())
        assertEquals(1, deck.summary.wanted)
        assertEquals(listOf("Action", "Sci-Fi"), deck.summary.genres.map { it.name }.sorted())
    }

    @Test
    fun `taste twins agree on the movies both swiped the same way`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-twin", name = "Alice")
        val bob = orm.insertUser("bob-twin", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val ids = listOf("tt0133093", "tt0234215", "tt0111161", "tt0110912", "tt0068646")
        runBlocking {
            ids.forEach { service.swipe(alice, it, SwipeWay.WANT, now) }
            ids.forEachIndexed { i, id -> service.swipe(bob, id, if (i < 4) SwipeWay.WANT else SwipeWay.PASS, now) }
        }
        val twin = runBlocking { service.deckOf(alice) }.twins.single()
        assertEquals("Bob", twin.user.name)
        assertEquals(5, twin.shared)
        assertEquals(80, twin.percent)
    }

    @Test
    fun `a round with a friend finds the movies both want, and a club's round the most wanted`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-round-swipe", name = "Alice")
        val bob = orm.insertUser("bob-round-swipe", name = "Bob")
        val carol = orm.insertUser("carol-round-swipe", name = "Carol")
        assertNull(runBlocking { service.startRound(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) })
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val round = runBlocking { service.startRound(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        runBlocking {
            service.swipeInRound(alice, round.id, "tt0133093", SwipeWay.WANT, now)
            service.swipeInRound(alice, round.id, "tt0111161", SwipeWay.WANT, now)
            service.swipeInRound(bob, round.id, "tt0133093", SwipeWay.WANT, now)
            service.swipeInRound(bob, round.id, "tt0111161", SwipeWay.PASS, now)
        }
        var view = runBlocking { service.roundOf(alice, round.id, now) }!!
        assertEquals(listOf("The Matrix"), view.results.map { it.movie.primaryTitle })
        assertEquals(4, view.cards.size)
        // A round's swipes stay with the round: nothing went on the watchlist.
        assertFalse(orm.repository<WatchlistRepository>().contains(alice, orm.repository<MovieRepository>().getById("tt0133093")))

        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(listOf(ClubMember(club, alice, true, now), ClubMember(club, carol, false, now)))
        val clubRound = runBlocking { service.startRound(alice, Opponents(GameMode.CLUB, clubId = club.id), now) }!!
        runBlocking {
            service.swipeInRound(carol, clubRound.id, "tt0110912", SwipeWay.WANT, now)
            service.swipeInRound(alice, clubRound.id, "tt0110912", SwipeWay.WANT, now)
            service.swipeInRound(alice, clubRound.id, "tt0068646", SwipeWay.WANT, now)
        }
        view = runBlocking { service.roundOf(carol, clubRound.id, now) }!!
        assertEquals(listOf("Pulp Fiction" to 2, "The Godfather" to 1), view.results.map { it.movie.primaryTitle to it.wantedBy.size })
        assertEquals(2, view.players.size)
        // Closed after two days: no more swipes.
        val later = now.plus(Duration.ofHours(49))
        runBlocking { service.swipeInRound(carol, clubRound.id, "tt0068646", SwipeWay.WANT, later) }
        view = runBlocking { service.roundOf(carol, clubRound.id, later) }!!
        assertTrue(view.over)
        assertNotNull(view.results.firstOrNull { it.movie.id == "tt0068646" && it.wantedBy.size == 1 })
    }
}
