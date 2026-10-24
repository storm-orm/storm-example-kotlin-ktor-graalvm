package st.orm.demo.imdb

import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.CheckInRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.FriendService
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
        orm.repository<FriendRequestRepository>(),
        orm.repository<ClubRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<CheckInRepository>()
    )

    @Test
    fun `friends see what a friend watched as far as the friend shares it`(orm: ORMTemplate) {
        val service = service(orm)
        val movies = orm.repository<MovieRepository>()
        val users = orm.repository<UserRepository>()
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val friendships = orm.repository<FriendshipRepository>()
        friendships.befriend(alice, bob, Instant.now())
        friendships.befriend(alice, carol, Instant.now())
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
        assertNull(private.single().name)
        assertNull(private.single().seenAt)
        assertEquals("A friend", private.single().caption)

        // The markers: the movies friends saw, history included, as far as each shares; Bob shares nothing now.
        assertEquals(mapOf("tt0111161" to listOf<String?>(null)), runBlocking { service.seenByFriends(alice) })

        // Either side ends a friendship, both ways.
        runBlocking { service.remove(bob, alice.id) }
        assertEquals(listOf("Carol"), runBlocking { service.friendsOf(alice) }.friends.map { it.name })
    }
}
