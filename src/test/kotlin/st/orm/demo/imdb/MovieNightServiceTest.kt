package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.NightMethod
import st.orm.demo.imdb.model.SwipeWay
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.MovieListItemRepository
import st.orm.demo.imdb.repository.MovieListRepository
import st.orm.demo.imdb.repository.MovieNightCandidateRepository
import st.orm.demo.imdb.repository.MovieNightProviderRepository
import st.orm.demo.imdb.repository.MovieNightRepository
import st.orm.demo.imdb.repository.MovieNightVoteRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.repository.UserWatchProviderRepository
import st.orm.demo.imdb.repository.WatchProviderRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.service.ListService
import st.orm.demo.imdb.service.MovieNightService
import st.orm.demo.imdb.service.Planned
import st.orm.demo.imdb.service.TmdbClient
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.entityId
import st.orm.test.StormTest
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieNightServiceTest {

    private fun service(orm: ORMTemplate) = MovieNightService(
        orm.repository<ClubRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<MovieNightRepository>(),
        orm.repository<MovieNightProviderRepository>(),
        orm.repository<MovieNightCandidateRepository>(),
        orm.repository<MovieNightVoteRepository>(),
        orm.repository<MovieRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<WatchlistRepository>(),
        orm.repository<RecommendationRepository>(),
        orm.repository<MovieListRepository>(),
        orm.repository<MovieListItemRepository>(),
        orm.repository<UserWatchProviderRepository>(),
        orm.repository<WatchProviderRepository>(),
        orm.repository<CheckInRepository>(),
        tmdbService(orm, TmdbClient(null, jacksonObjectMapper())),
        swipeService(orm), orm.repository(), orm.repository(), orm.repository()
    )

    private val now = Instant.parse("2026-10-26T10:00:00Z")

    private fun club(orm: ORMTemplate, vararg members: Pair<User, Boolean>): Club {
        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(members.map { (user, admin) -> ClubMember(club, user, admin, now) })
        return club
    }

    @Test
    fun `a night anywhere puts the members' wanted movies nobody saw to the vote, most wanted first`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-night", name = "Alice")
        val bob = orm.insertUser("bob-night", name = "Bob")
        val carol = orm.insertUser("carol-night", name = "Carol")
        val club = club(orm, alice to true, bob to false)
        val movies = orm.repository<MovieRepository>()
        val watchlist = orm.repository<WatchlistRepository>()
        // Pulp Fiction on both watchlists, The Godfather on one; Shawshank too, but Bob saw it.
        watchlist.insert(listOf(
            Watchlist(alice, movies.getById("tt0110912"), now), Watchlist(bob, movies.getById("tt0110912"), now),
            Watchlist(bob, movies.getById("tt0068646"), now), Watchlist(alice, movies.getById("tt0111161"), now)
        ))
        orm.repository<MovieSeenRepository>().insert(MovieSeen(bob, movies.getById("tt0111161"), now))
        val service = service(orm)

        assertEquals(Planned.NotAllowed, runBlocking { service.plan(carol, club.id, "Ours", LocalDate.parse("2026-10-30"), emptySet(), false, null, "NL", now) })
        val planned = runBlocking { service.plan(alice, club.id, "  ", LocalDate.parse("2026-10-30"), emptySet(), false, null, "NL", now) }
        val night = (planned as Planned.Night).night
        assertEquals("Movie night", night.title)

        val view = runBlocking { service.nightOf(bob, night.id, LocalDate.parse("2026-10-26")) }!!
        assertEquals(listOf("Pulp Fiction", "The Godfather"), view.candidates.map { it.movie.primaryTitle })
        assertEquals("On 2 watchlists", view.candidates.first().candidate.reason)
        assertFalse(view.canDecide)
        assertNull(runBlocking { service.nightOf(carol, night.id) })
    }

    @Test
    fun `a night chosen by swiping runs the club's round on the deck, and the most swiped right wins`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-swipe-night", name = "Alice")
        val bob = orm.insertUser("bob-swipe-night", name = "Bob")
        val club = club(orm, alice to true, bob to false)
        val movies = orm.repository<MovieRepository>()
        orm.repository<WatchlistRepository>().insert(listOf(Watchlist(alice, movies.getById("tt0110912"), now), Watchlist(bob, movies.getById("tt0068646"), now)))
        val service = service(orm)
        val night = (runBlocking { service.plan(alice, club.id, "Swipe night", LocalDate.parse("2026-10-30"), emptySet(), false, null, "NL", now, NightMethod.SWIPE) } as Planned.Night).night
        assertEquals(NightMethod.SWIPE, night.method)
        val matchId = night.swipeMatch!!.entityId()

        // Both want The Godfather; Alice passes on Pulp Fiction.
        val swipes = swipeService(orm)
        runBlocking { swipes.swipeInRound(bob, matchId, "tt0068646", SwipeWay.WANT, now) }
        runBlocking { swipes.swipeInRound(alice, matchId, "tt0068646", SwipeWay.WANT, now) }
        runBlocking { swipes.swipeInRound(alice, matchId, "tt0110912", SwipeWay.PASS, now) }
        val view = runBlocking { service.nightOf(bob, night.id) }!!
        assertEquals(matchId, view.swipeMatchId)
        assertEquals(2, view.candidates.first { it.movie.id == "tt0068646" }.voters.size)
        assertTrue(view.myVotes.contains("tt0068646"))
        assertEquals(2, view.voterCount)

        runBlocking { service.decide(alice, night.id) }
        assertEquals("The Godfather", runBlocking { service.nightOf(alice, night.id) }!!.night.winner!!.primaryTitle)
    }

    @Test
    fun `members vote once each, the host picks the most voted, and the night come they check it in`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-vote", name = "Alice")
        val bob = orm.insertUser("bob-vote", name = "Bob")
        val club = club(orm, alice to false, bob to false)
        val lists = ListService(orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository())
        val list = runBlocking { lists.create(alice, "Friday picks", null, club.id, "tt0133093") }!!
        runBlocking { lists.toggle(bob, list.id, "tt0211915") }
        val service = service(orm)
        val day = LocalDate.parse("2026-10-24")
        val night = (runBlocking { service.plan(alice, club.id, "Friday", day, emptySet(), false, list.id, "NL", now) } as Planned.Night).night

        // Bob votes for both, Alice for Amélie; Bob takes a vote back and gives it again.
        runBlocking { service.vote(alice, night.id, "tt0211915") }
        runBlocking { service.vote(bob, night.id, "tt0133093") }
        runBlocking { service.vote(bob, night.id, "tt0211915") }
        runBlocking { service.vote(bob, night.id, "tt0133093") }
        runBlocking { service.vote(bob, night.id, "tt0133093") }
        // Not a candidate: no vote.
        runBlocking { service.vote(bob, night.id, "tt0068646") }
        var view = runBlocking { service.nightOf(alice, night.id, day) }!!
        assertEquals(2, view.voterCount)
        assertEquals(setOf("tt0211915"), view.myVotes)
        assertEquals(setOf("tt0211915", "tt0133093"), runBlocking { service.nightOf(bob, night.id, day) }!!.myVotes)
        assertEquals(listOf(2, 1), view.candidates.sortedBy { it.movie.id != "tt0211915" }.map { it.voters.size })
        assertEquals("From Friday picks", view.candidates.first { it.movie.id == "tt0133093" }.candidate.reason)

        // Bob is no admin and not the host: he cannot decide. Alice hosts.
        runBlocking { service.decide(bob, night.id) }
        assertFalse(runBlocking { service.nightOf(alice, night.id, day) }!!.decided)
        runBlocking { service.decide(alice, night.id) }
        view = runBlocking { service.nightOf(alice, night.id, day) }!!
        assertEquals("Amélie", view.night.winner!!.primaryTitle)
        assertTrue(view.canCheckIn)
        assertFalse(view.checkedIn)
        // Voting is closed once decided; reopening opens it again.
        runBlocking { service.vote(alice, night.id, "tt0133093") }
        assertEquals(setOf("tt0211915"), runBlocking { service.nightOf(alice, night.id, day) }!!.myVotes)
        runBlocking { service.decide(alice, night.id, reopen = true) }
        assertFalse(runBlocking { service.nightOf(alice, night.id, day) }!!.decided)
        assertEquals(club.id, runBlocking { service.remove(alice, night.id) })
        assertTrue(runBlocking { service.nightsOf(club) }.isEmpty())
    }

    @Test
    fun `a night nobody can vote on is not planned`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-empty", name = "Alice")
        val club = club(orm, alice to true)
        assertEquals(Planned.NothingFound, runBlocking { service(orm).plan(alice, club.id, "", LocalDate.parse("2026-10-30"), emptySet(), false, null, "NL", now) })
    }
}
