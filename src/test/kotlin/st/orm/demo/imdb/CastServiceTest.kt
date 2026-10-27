package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.Credit
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.Level
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.service.CastMatcher
import st.orm.demo.imdb.service.CastPhase
import st.orm.demo.imdb.service.CastService
import st.orm.demo.imdb.service.MatchService
import st.orm.demo.imdb.service.NameVerdict
import st.orm.demo.imdb.service.Opponents
import st.orm.demo.imdb.service.TmdbClient
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class CastServiceTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    /**
     * Rounds of the fixture's one movie with more than 2.5 million votes, The
     * Shawshank Redemption, whose credits without TMDB are IMDb's two: Tim
     * Robbins and Morgan Freeman.
     */
    private fun service(orm: ORMTemplate) = CastService(
        matchService(orm),
        orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
        orm.repository(), orm.repository(), orm.repository(), tmdbService(orm, TmdbClient(null, jacksonObjectMapper())), poolService(orm),
        minimumVotes = 2_500_000, minimumCredits = 2
    )

    /** A user at Supporting, which the game asks of every player. */
    private fun ORMTemplate.supporting(id: String, name: String) = liftTo(insertUser(id, name = name), Level.SUPPORTING)

    @Test
    fun `a name matches whatever its case and accents, with a typo in a long name, or a surname that only one has`() {
        val credits = listOf(Credit("Penélope Cruz"), Credit("Francis Ford Coppola", director = true), Credit("Sofia Coppola"), Credit("Al Pacino"))
        fun verdict(answer: String, named: Set<String> = emptySet()) = CastMatcher.match(answer, credits, named).verdict
        assertEquals(NameVerdict.RIGHT, verdict("penelope cruz"))
        assertEquals(NameVerdict.RIGHT, verdict("Penelope Crux"))
        assertEquals(NameVerdict.RIGHT, verdict("Pacino"))
        assertEquals(NameVerdict.UNCLEAR, verdict("Coppola"))
        assertEquals(NameVerdict.RIGHT, verdict("Francis Ford Copola"))
        assertEquals(NameVerdict.ALREADY, verdict("Al Pacino", named = setOf("Al Pacino")))
        assertEquals(NameVerdict.WRONG, verdict("Al"))
        assertEquals(NameVerdict.WRONG, verdict("Robert De Niro"))
    }

    @Test
    fun `on one's own a player names as many as they know, until three wrong names`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.supporting("alice-cast", "Alice")
        val match = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        var round = runBlocking { service.roundOf(alice, match.id, now) }!!
        assertEquals("The Shawshank Redemption", round.movie.primaryTitle)
        assertEquals(CastPhase.NAMING, round.phase)
        assertEquals(2, round.creditCount)

        assertEquals(NameVerdict.RIGHT, runBlocking { service.name(alice, match.id, "robbins", now) })
        assertEquals(NameVerdict.ALREADY, runBlocking { service.name(alice, match.id, "Tim Robbins", now) })
        repeat(3) { assertEquals(NameVerdict.WRONG, runBlocking { service.name(alice, match.id, "Brad Pitt", now) }) }
        round = runBlocking { service.roundOf(alice, match.id, now) }!!
        assertEquals(CastPhase.OVER, round.phase)
        assertEquals(1, round.right)
        // The whole cast shown, the named first.
        assertEquals(listOf("Tim Robbins" to true, "Morgan Freeman" to false), round.reveal.map { it.credit.name to it.named })
        assertNull(runBlocking { service.name(alice, match.id, "Morgan Freeman", now) })
    }

    @Test
    fun `with a friend the bids stay hidden until both bid, and the highest names them all`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.supporting("alice-bid", "Alice")
        val below = orm.insertUser("bob-bid", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, below, now)
        // Bob is not at Supporting: played from what both watched, the round needs him there.
        assertNull(runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = below.id), now) })
        val bob = orm.liftTo(below, Level.SUPPORTING)
        val match = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!

        assertTrue(runBlocking { service.bid(alice, match.id, 2, now) })
        var round = runBlocking { service.roundOf(bob, match.id, now) }!!
        assertEquals(CastPhase.BIDDING, round.phase)
        assertNull(round.bids.single().count)
        // Nobody names while the bidding is open.
        assertNull(runBlocking { service.name(alice, match.id, "Tim Robbins", now) })

        assertTrue(runBlocking { service.bid(bob, match.id, 1, now.plusSeconds(60)) })
        round = runBlocking { service.roundOf(bob, match.id, now.plusSeconds(60)) }!!
        assertEquals(CastPhase.NAMING, round.phase)
        assertEquals("Alice", round.namer!!.name)
        assertEquals(2, round.target)
        assertEquals(listOf(2, 1), round.bids.map { it.count })
        assertNull(runBlocking { service.name(bob, match.id, "Tim Robbins", now) })

        val later = now.plusSeconds(120)
        assertEquals(NameVerdict.RIGHT, runBlocking { service.name(alice, match.id, "Morgan Freeman", later) })
        assertEquals(NameVerdict.RIGHT, runBlocking { service.name(alice, match.id, "Tim Robbins", later) })
        round = runBlocking { service.roundOf(bob, match.id, later) }!!
        assertEquals(CastPhase.OVER, round.phase)
        assertTrue(round.won)
        assertEquals("Alice named all 2", service.standingOf(bob, match, later).second)
    }

    @Test
    fun `in a club a tie goes to the first bid, and a namer who runs out of time loses`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.supporting("alice-club-cast", "Alice")
        val bob = orm.supporting("bob-club-cast", "Bob")
        val carol = orm.supporting("carol-club-cast", "Carol")
        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(listOf(ClubMember(club, alice, true, now), ClubMember(club, bob, false, now), ClubMember(club, carol, false, now)))
        val match = runBlocking { service.start(alice, Opponents(GameMode.CLUB, clubId = club.id), now) }!!
        assertTrue(runBlocking { service.bid(bob, match.id, 1, now) })
        assertTrue(runBlocking { service.bid(alice, match.id, 1, now.plusSeconds(10)) })
        // Carol never bids: the bidding closes after a day, and Bob, first with the top bid, names.
        val dayLater = now.plus(Duration.ofHours(25))
        var round = runBlocking { service.roundOf(carol, match.id, dayLater) }!!
        assertEquals(CastPhase.NAMING, round.phase)
        assertEquals("Bob", round.namer!!.name)
        assertFalse(round.youBid)
        // A day more without his name: the round is lost.
        round = runBlocking { service.roundOf(carol, match.id, dayLater.plus(Duration.ofHours(24))) }!!
        assertEquals(CastPhase.OVER, round.phase)
        assertFalse(round.won)
    }
}
