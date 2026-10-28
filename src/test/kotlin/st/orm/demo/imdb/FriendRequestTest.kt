package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubInviteRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.ClubService
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.FriendStatus
import st.orm.demo.imdb.service.TasteOverlap
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

/** Friend requests between club members: one tap to ask, one to accept, and no word of a "not now". */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class FriendRequestTest {

    private fun friendService(orm: ORMTemplate) = FriendService(
        orm.repository<UserRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<FriendInviteRepository>(),
        orm.repository<FriendRequestRepository>(),
        orm.repository<ClubRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<CheckInRepository>(),
        TasteOverlap(orm.repository<MovieSeenRepository>(), orm.repository<MovieGenreRepository>())
    )

    private fun clubService(orm: ORMTemplate) = ClubService(
        orm.repository<UserRepository>(),
        orm.repository<ClubRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<ClubInviteRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<FriendRequestRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<CheckInRepository>(),
        TasteOverlap(orm.repository<MovieSeenRepository>(), orm.repository<MovieGenreRepository>())
    )

    private fun ORMTemplate.join(club: Club, user: User) =
        repository<ClubMemberRepository>().insert(ClubMember(club, user, admin = false, joinedAt = Instant.now()))

    /** Where [user] stands with each member of the club, by name. */
    private fun statuses(orm: ORMTemplate, user: User, club: Club): Map<String?, FriendStatus> {
        val view = runBlocking { clubService(orm).clubOf(user, club.id) }!!
        return view.members.associate { it.user.name to view.statusOf(it) }
    }

    private fun friendNames(orm: ORMTemplate, user: User) =
        runBlocking { friendService(orm).friendsOf(user) }.friends.map { it.name }

    @Test
    fun `a member asks another, who accepts, and the two are friends`(orm: ORMTemplate) {
        val friends = friendService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val club = runBlocking { clubService(orm).create(alice, "Office film club") }
        orm.join(club, bob)

        runBlocking { friends.request(alice, bob.id, club.id) }
        assertEquals(mapOf("Alice" to FriendStatus.YOU, "Bob" to FriendStatus.REQUESTED), statuses(orm, alice, club))
        assertEquals(mapOf("Alice" to FriendStatus.ASKED_YOU, "Bob" to FriendStatus.YOU), statuses(orm, bob, club))
        // Bob has one request to answer, from the club it was sent from; Alice sees it as sent.
        assertEquals(1L, runBlocking { friends.countRequests(bob) })
        val request = runBlocking { friends.friendsOf(bob) }.requests.single()
        assertEquals("Alice" to "Office film club", request.requester.name to request.club.name)
        assertEquals(listOf("Bob"), runBlocking { friends.friendsOf(alice) }.sent.map { it.recipient.name })

        runBlocking { friends.accept(bob, alice.id) }
        assertEquals(listOf("Bob"), friendNames(orm, alice))
        assertEquals(listOf("Alice"), friendNames(orm, bob))
        assertEquals(FriendStatus.FRIENDS, statuses(orm, alice, club)["Bob"])
        // The request is gone with it.
        assertTrue(orm.repository<FriendRequestRepository>().findAll().isEmpty())
    }

    @Test
    fun `asking someone who asked you makes you friends at once`(orm: ORMTemplate) {
        val friends = friendService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val club = runBlocking { clubService(orm).create(alice, "Office film club") }
        orm.join(club, bob)

        runBlocking { friends.request(alice, bob.id, club.id) }
        runBlocking { friends.request(bob, alice.id, club.id) }

        assertEquals(listOf("Bob"), friendNames(orm, alice))
        assertTrue(orm.repository<FriendRequestRepository>().findAll().isEmpty())
    }

    @Test
    fun `not now hides a request without telling, and the requester may withdraw it`(orm: ORMTemplate) {
        val friends = friendService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val club = runBlocking { clubService(orm).create(alice, "Office film club") }
        orm.join(club, bob)
        orm.join(club, carol)

        // Bob puts Alice's request off: gone from his list and his count, still sent for Alice.
        runBlocking { friends.request(alice, bob.id, club.id) }
        runBlocking { friends.dismiss(bob, alice.id) }
        assertTrue(runBlocking { friends.friendsOf(bob) }.requests.isEmpty())
        assertEquals(0L, runBlocking { friends.countRequests(bob) })
        assertEquals(FriendStatus.REQUESTED, statuses(orm, alice, club)["Bob"])
        // Should Bob change his mind, asking Alice makes them friends.
        runBlocking { friends.request(bob, alice.id, club.id) }
        assertEquals(listOf("Alice"), friendNames(orm, bob))

        // Alice asks Carol, then thinks better of it.
        runBlocking { friends.request(alice, carol.id, club.id) }
        runBlocking { friends.withdraw(alice, carol.id) }
        assertEquals(0L, runBlocking { friends.countRequests(carol) })
        assertEquals(FriendStatus.NONE, statuses(orm, alice, club)["Carol"])

        // Ending a friendship is up to either side, and leaves no request behind.
        runBlocking { friends.remove(bob, alice.id) }
        assertTrue(friendNames(orm, alice).isEmpty())
        assertEquals(FriendStatus.NONE, statuses(orm, alice, club)["Bob"])
    }

    @Test
    fun `only members of a shared club ask, and leaving the club takes the request along`(orm: ORMTemplate) {
        val friends = friendService(orm)
        val clubs = clubService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val stranger = orm.insertUser("dave", name = "Dave")
        val club = runBlocking { clubs.create(alice, "Office film club") }
        orm.join(club, bob)

        // Dave is in no club with Alice: nothing comes of his request.
        runBlocking { friends.request(stranger, alice.id, club.id) }
        assertEquals(0L, runBlocking { friends.countRequests(alice) })

        runBlocking { friends.request(bob, alice.id, club.id) }
        assertEquals(1L, runBlocking { friends.countRequests(alice) })
        runBlocking { clubs.leave(bob, club.id) }
        assertEquals(0L, runBlocking { friends.countRequests(alice) })
    }

    @Test
    fun `a sign-in that joins two accounts moves the requests`(orm: ORMTemplate) {
        val friends = friendService(orm)
        val requests = orm.repository<FriendRequestRepository>()
        val alice = orm.insertUser("alice", name = "Alice")
        val phone = orm.insertUser("alice-phone", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val club = runBlocking { clubService(orm).create(bob, "Office film club") }
        orm.join(club, alice)
        orm.join(club, phone)
        runBlocking { friends.request(bob, phone.id, club.id) }
        runBlocking { friends.request(phone, alice.id, club.id) }

        requests.moveTo(phone, alice)

        // Bob's request now waits for Alice; the one between her own two accounts is gone.
        assertEquals(listOf("bob" to "alice"), requests.findAll().map { it.requester.id to it.recipient.id })
    }
}
