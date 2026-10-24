package st.orm.demo.imdb

import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.repository.CheckInRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.InviteState
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class FriendServiceTest {

    private fun service(orm: ORMTemplate) = FriendService(
        orm.repository<UserRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<FriendInviteRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<CheckInRepository>()
    )

    @Test
    fun `an invite link, once accepted, makes two friends, and is used up`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val token = runBlocking { service.createInvite(alice) }

        assertEquals(InviteState.Own, runBlocking { service.invite(token, alice) })
        assertEquals(InviteState.Open(alice), runBlocking { service.invite(token, bob) })
        assertFalse(runBlocking { service.accept(token, alice) })
        assertTrue(runBlocking { service.accept(token, bob) })

        assertEquals(listOf("Bob"), runBlocking { service.friendsOf(alice) }.friends.map { it.name })
        assertEquals(listOf("Alice"), runBlocking { service.friendsOf(bob) }.friends.map { it.name })
        assertEquals(InviteState.Gone, runBlocking { service.invite(token, bob) })

        // Either side ends it, both ways.
        runBlocking { service.remove(bob, alice.id) }
        assertTrue(runBlocking { service.friendsOf(alice) }.friends.isEmpty())
    }

    @Test
    fun `a public link befriends everyone who accepts it, until it is replaced`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val dave = orm.insertUser("dave", name = "Dave")
        val link = runBlocking { service.publicInvite(alice) }
        // Asked again, it is the same link; a year on it still works.
        assertEquals(link, runBlocking { service.publicInvite(alice) })
        val later = Instant.now().plus(java.time.Duration.ofDays(365))

        assertTrue(runBlocking { service.accept(link, bob) })
        assertTrue(runBlocking { service.accept(link, carol, now = later) })
        assertEquals(listOf("Bob", "Carol"), runBlocking { service.friendsOf(alice) }.friends.map { it.name })

        // Replaced, the old link stops working.
        val replaced = runBlocking { service.replacePublicInvite(alice) }
        assertEquals(InviteState.Gone, runBlocking { service.invite(link, dave) })
        assertEquals(InviteState.Open(alice), runBlocking { service.invite(replaced, dave) })
    }

    @Test
    fun `an invite expires after two weeks`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val created = Instant.parse("2026-10-01T10:00:00Z")
        val token = runBlocking { service.createInvite(alice, now = created) }

        assertEquals(InviteState.Gone, runBlocking { service.invite(token, bob, now = created.plus(FriendService.INVITE_VALIDITY).plusSeconds(1)) })
        assertFalse(runBlocking { service.accept(token, bob, now = created.plus(FriendService.INVITE_VALIDITY).plusSeconds(1)) })
    }

    @Test
    fun `friends see what a friend watched as far as the friend shares it`(orm: ORMTemplate) {
        val service = service(orm)
        val movies = orm.repository<MovieRepository>()
        val users = orm.repository<UserRepository>()
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        runBlocking { service.accept(service.createInvite(alice), bob) }
        runBlocking { service.accept(service.createInvite(alice), carol) }
        val seen = orm.repository<MovieSeenRepository>()
        val checkIns = orm.repository<CheckInRepository>()
        fun checkIn(user: st.orm.demo.imdb.model.User, movieId: String, at: String) {
            seen.insert(MovieSeen(user, movies.getById(movieId), Instant.parse(at)))
            checkIns.insert(CheckIn(user, movies.getById(movieId), Instant.parse(at)))
        }
        checkIn(bob, "tt0133093", "2026-10-20T10:00:00Z")
        checkIn(carol, "tt0111161", "2026-10-21T10:00:00Z")
        // Bob caught up on The Godfather, seen long ago, without a check-in: it stays out of the feed.
        seen.insert(MovieSeen(bob, movies.getById("tt0068646"), Instant.parse("2026-10-23T10:00:00Z")))
        // Alice herself is no friend of Alice: her own check-ins stay out of her feed.
        checkIn(alice, "tt0068646", "2026-10-22T10:00:00Z")

        // Everything shared by default: the latest first, with names and days.
        val feed = runBlocking { service.friendsOf(alice) }.feed
        assertEquals(listOf("Carol · 21 Oct 2026", "Bob · 20 Oct 2026"), feed.map { it.caption })

        // Carol keeps her name and days to herself; Bob shares nothing.
        runBlocking { service.updateSharing(users.getById("carol"), shareSeen = true, shareName = false, shareDates = false) }
        runBlocking { service.updateSharing(users.getById("bob"), shareSeen = false, shareName = true, shareDates = true) }
        val private = runBlocking { service.friendsOf(alice) }.feed
        assertEquals(listOf("The Shawshank Redemption"), private.map { it.movie.primaryTitle })
        assertNull(private.single().friendName)
        assertNull(private.single().seenAt)
        assertEquals("A friend", private.single().caption)

        // The markers: the movies friends saw, history included, as far as each shares; Bob shares nothing now.
        assertEquals(mapOf("tt0111161" to listOf<String?>(null)), runBlocking { service.seenByFriends(alice) })
    }
}
