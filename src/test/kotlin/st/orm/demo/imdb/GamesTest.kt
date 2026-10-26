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
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.repository.ChainLinkRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GameCardRepository
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.ChainMove
import st.orm.demo.imdb.service.ChainService
import st.orm.demo.imdb.service.HigherLowerService
import st.orm.demo.imdb.service.MatchService
import st.orm.demo.imdb.service.Opponents
import st.orm.demo.imdb.service.PlayService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.StormTest
import java.time.Duration
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class GamesTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun matches(orm: ORMTemplate) = MatchService(
        orm.repository<GameMatchRepository>(), orm.repository<GamePlayerRepository>(), orm.repository<FriendshipRepository>(),
        orm.repository<ClubRepository>(), orm.repository<ClubMemberRepository>(), orm.repository<UserRepository>()
    )

    private fun higherLower(orm: ORMTemplate) = HigherLowerService(
        matches(orm), orm.repository<GamePlayerRepository>(), orm.repository<GameCardRepository>(),
        orm.repository<RatingRepository>(), orm.repository<ClubMemberRepository>(), orm.repository<UserRepository>()
    )

    private fun chains(orm: ORMTemplate) = ChainService(
        matches(orm), orm.repository<GameMatchRepository>(), orm.repository<GamePlayerRepository>(), orm.repository<ChainLinkRepository>(),
        orm.repository<PrincipalRepository>(), orm.repository<RatingRepository>(), orm.repository<MovieRepository>(),
        orm.repository<PersonRepository>(), orm.repository<UserRepository>()
    )

    @Test
    fun `higher or lower goes on with every right call and ends at the first wrong one`(orm: ORMTemplate) {
        val service = higherLower(orm)
        val alice = orm.insertUser("alice-hl", name = "Alice")
        val match = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        var game = runBlocking { service.matchOf(alice, match.id, now) }!!
        // The fixture's six movies, no two in a row with the same rating.
        assertEquals(6, game.cardCount)
        assertTrue(game.playing)

        repeat(2) {
            val higher = game.next!!.rating > game.current!!.rating
            runBlocking { service.answer(alice, match.id, higher, now) }
            game = runBlocking { service.matchOf(alice, match.id, now) }!!
        }
        assertEquals(2, game.streak)
        val wrong = game.next!!.rating < game.current!!.rating
        runBlocking { service.answer(alice, match.id, wrong, now) }
        game = runBlocking { service.matchOf(alice, match.id, now) }!!
        assertTrue(game.finished)
        assertFalse(game.playing)
        assertEquals(2, game.streak)
        assertNotNull(game.missed)
        assertEquals(2, game.best)
        // Nobody else sees a game on one's own.
        assertNull(runBlocking { service.matchOf(orm.insertUser("bob-hl", name = "Bob"), match.id, now) })
    }

    @Test
    fun `a friend's challenge is for friends only, and a club's round is joined by playing it until it closes`(orm: ORMTemplate) {
        val service = higherLower(orm)
        val alice = orm.insertUser("alice-round", name = "Alice")
        val bob = orm.insertUser("bob-round", name = "Bob")
        val carol = orm.insertUser("carol-round", name = "Carol")
        assertNull(runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) })
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val challenge = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        // A round deals up to 25 movies: the fixture's six here.
        assertEquals(6, orm.repository<GameCardRepository>().findCards(challenge).size)
        assertEquals(listOf("Alice", "Bob"), runBlocking { service.matchOf(bob, challenge.id, now) }!!.players.mapNotNull { it.user.name }.sorted())

        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(listOf(ClubMember(club, alice, true, now), ClubMember(club, carol, false, now)))
        val round = runBlocking { service.start(alice, Opponents(GameMode.CLUB, clubId = club.id), now) }!!
        assertEquals(1, runBlocking { service.matchOf(alice, round.id, now) }!!.players.size)
        runBlocking { service.answer(carol, round.id, true, now) }
        assertEquals(2, runBlocking { service.matchOf(carol, round.id, now) }!!.players.size)
        // Bob is no member: he neither sees nor joins it; and after two days the round is closed.
        assertNull(runBlocking { service.matchOf(bob, round.id, now) })
        val later = now.plus(Duration.ofHours(49))
        assertTrue(runBlocking { service.matchOf(alice, round.id, later) }!!.over)
    }

    /** Morgan Freeman in The Matrix Reloaded: its third actor, so the chain starts there, the one movie with a cast of three. */
    private fun castOfThree(orm: ORMTemplate) {
        orm.repository<PrincipalRepository>().insert(
            Principal(orm.repository<MovieRepository>().getById("tt0234215").ref(), 3, orm.repository<PersonRepository>().getById("nm0000151"), "actor", "Somebody")
        )
    }

    @Test
    fun `friends chain movies through shared actors in turn, and a turn not taken passes on`(orm: ORMTemplate) {
        castOfThree(orm)
        val service = chains(orm)
        val alice = orm.insertUser("alice-chain", name = "Alice")
        val bob = orm.insertUser("bob-chain", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val match = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        var chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertEquals("The Matrix Reloaded", chain.last.movie.primaryTitle)
        // Alice, the host, plays first.
        assertTrue(chain.yourTurn)
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne", "Morgan Freeman"), chain.cast.map { it.primaryName })

        assertEquals(ChainMove.NOT_YOUR_TURN, runBlocking { service.move(bob, match.id, "tt0133093", now) })
        assertEquals(ChainMove.NO_SHARED_ACTOR, runBlocking { service.move(alice, match.id, "tt0110912", now) })
        assertEquals(ChainMove.ADDED, runBlocking { service.move(alice, match.id, "tt0133093", now) })
        assertEquals(ChainMove.ALREADY_IN, runBlocking { service.move(bob, match.id, "tt0234215", now.plusSeconds(60)) })
        chain = runBlocking { service.chainOf(alice, match.id, now.plusSeconds(120)) }!!
        assertEquals("Keanu Reeves", chain.last.via!!.primaryName)
        assertEquals(mapOf("Alice" to 1, "Bob" to 0), chain.players.associate { it.user.name to it.score })
        assertFalse(chain.yourTurn)

        // Bob lets a day go by: the turn has passed back to Alice.
        val dayLater = now.plus(Duration.ofHours(25))
        chain = runBlocking { service.chainOf(alice, match.id, dayLater) }!!
        assertTrue(chain.yourTurn)
        assertEquals(1, chain.turn!!.skipped)
        // Alice is stuck: the chain is over, and she won.
        runBlocking { service.giveUp(alice, match.id, dayLater) }
        chain = runBlocking { service.chainOf(alice, match.id, dayLater) }!!
        assertTrue(chain.over)
        assertEquals("Alice", chain.standings.first().user.name)
    }

    @Test
    fun `on one's own the house answers every movie, and loses when it finds none`(orm: ORMTemplate) {
        castOfThree(orm)
        // Keanu Reeves in Pulp Fiction too, for the house to answer The Matrix with.
        orm.repository<PrincipalRepository>().insert(
            Principal(orm.repository<MovieRepository>().getById("tt0110912").ref(), 2, orm.repository<PersonRepository>().getById("nm0000206"), "actor", "Somebody")
        )
        val service = chains(orm)
        val alice = orm.insertUser("alice-house", name = "Alice")
        val match = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        assertEquals(ChainMove.ADDED, runBlocking { service.move(alice, match.id, "tt0133093", now) })
        var chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertEquals("Pulp Fiction", chain.last.movie.primaryTitle)
        assertNull(chain.last.player)
        assertTrue(chain.yourTurn)
        assertFalse(chain.over)
        // Nothing shares an actor with Pulp Fiction but the movies in the chain: Alice is stuck, and the house wins.
        runBlocking { service.giveUp(alice, match.id, now) }
        chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertTrue(chain.over)
        assertEquals(1, chain.housePoints)

        val play = PlayService(matches(orm), orm.repository(), orm.repository(), service, orm.repository())
        assertEquals(listOf("Over: 1 point"), runBlocking { play.playOf(alice, now) }.games.map { it.status })
    }
}
