package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubInviteRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.ClubSeen
import st.orm.demo.imdb.service.ClubService
import st.orm.demo.imdb.service.Leaving
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class ClubServiceTest {

    private fun service(orm: ORMTemplate) = ClubService(
        orm.repository<UserRepository>(),
        orm.repository<ClubRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<ClubInviteRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<FriendRequestRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<CheckInRepository>()
    )

    /** Makes the user a member, as accepting an invite does (InviteServiceTest covers the invites). */
    private fun ORMTemplate.join(club: Club, user: User) =
        repository<ClubMemberRepository>().insert(ClubMember(club, user, admin = false, joinedAt = Instant.now()))

    @Test
    fun `whoever starts a club is its first admin`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val club = runBlocking { service.create(alice, "  Office film club  ") }
        orm.join(club, bob)

        assertEquals("Office film club", club.name)
        val clubs = runBlocking { service.clubsOf(alice) }.clubs
        assertEquals(listOf("Office film club"), clubs.map { it.membership.club.name })
        assertTrue(clubs.single().membership.admin)
        assertEquals(2, clubs.single().memberCount)
        // Admins first, then by name.
        assertEquals(listOf("Alice", "Bob"), runBlocking { service.clubOf(bob, club.id) }!!.members.map { it.user.name })
        // A club's page is for its members only.
        assertNull(runBlocking { service.clubOf(orm.insertUser("carol", name = "Carol"), club.id) })
    }

    @Test
    fun `a club keeps an admin, and closes when its last member leaves`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val club = runBlocking { service.create(alice, "Office film club") }
        orm.join(club, bob)

        // The only admin neither steps down nor leaves while Bob remains; Bob, no admin, manages nothing.
        assertFalse(runBlocking { service.setAdmin(alice, club.id, alice.id, admin = false) })
        assertEquals(Leaving.LAST_ADMIN, runBlocking { service.leave(alice, club.id) })
        assertFalse(runBlocking { service.setAdmin(bob, club.id, bob.id, admin = true) })

        // With Bob made an admin, Alice may go.
        assertTrue(runBlocking { service.setAdmin(alice, club.id, bob.id, admin = true) })
        assertEquals(Leaving.LEFT, runBlocking { service.leave(alice, club.id) })
        assertEquals(listOf("Bob"), runBlocking { service.clubOf(bob, club.id) }!!.members.map { it.user.name })

        // The last member leaving closes the club.
        assertEquals(Leaving.CLOSED, runBlocking { service.leave(bob, club.id) })
        assertNull(orm.repository<ClubRepository>().findById(club.id))
    }

    @Test
    fun `admins rename the club and remove members, others cannot`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val club = runBlocking { service.create(alice, "Office film club") }
        orm.join(club, bob)
        orm.join(club, carol)

        runBlocking { service.rename(bob, club.id, "Bob's club") }
        runBlocking { service.remove(bob, club.id, carol.id) }
        assertEquals("Office film club", orm.repository<ClubRepository>().getById(club.id).name)
        assertEquals(3, runBlocking { service.clubOf(alice, club.id) }!!.members.size)

        runBlocking { service.rename(alice, club.id, "Thursday movie night") }
        runBlocking { service.remove(alice, club.id, carol.id) }
        assertEquals("Thursday movie night", orm.repository<ClubRepository>().getById(club.id).name)
        assertEquals(listOf("Alice", "Bob"), runBlocking { service.clubOf(alice, club.id) }!!.members.map { it.user.name })
    }

    @Test
    fun `members see what the others watched, as far as each shares it in the club`(orm: ORMTemplate) {
        val service = service(orm)
        val movies = orm.repository<MovieRepository>()
        val users = orm.repository<UserRepository>()
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val club = runBlocking { service.create(alice, "Office film club") }
        orm.join(club, bob)
        orm.join(club, carol)
        fun checkIn(user: User, movieId: String, at: String) {
            orm.repository<MovieSeenRepository>().insert(MovieSeen(user, movies.getById(movieId), Instant.parse(at)))
            orm.repository<CheckInRepository>().insert(CheckIn(user, movies.getById(movieId), Instant.parse(at)))
        }
        checkIn(bob, "tt0133093", "2026-10-20T10:00:00Z")
        checkIn(carol, "tt0133093", "2026-10-21T10:00:00Z")
        checkIn(carol, "tt0111161", "2026-10-22T10:00:00Z")

        // By default names are shared and days are not: members are not always friends.
        val view = runBlocking { service.clubOf(alice, club.id) }!!
        assertEquals(listOf("Carol", "Carol", "Bob"), view.feed.map { it.caption })
        // The Matrix is the one movie two members saw.
        assertEquals(listOf("tt0133093" to 2L), view.mostSeen.map { it.movie.id to it.seenCount })
        // The tags: Alice sees both, each under the club.
        assertEquals(
            mapOf("tt0133093" to listOf(ClubSeen("Bob", "Office film club"), ClubSeen("Carol", "Office film club")), "tt0111161" to listOf(ClubSeen("Carol", "Office film club"))),
            runBlocking { service.seenInClubs(alice) }.mapValues { (_, seen) -> seen.sortedBy { it.member } }
        )

        // Carol sets this club apart: no name, but the days; Bob stops sharing in clubs altogether.
        runBlocking { service.updateSharing(users.getById("carol"), club.id, shareSeen = true, shareName = false, shareDates = true) }
        runBlocking { service.updateDefaults(users.getById("bob"), shareSeen = false, shareName = true, shareDates = false) }
        val private = runBlocking { service.clubOf(alice, club.id) }!!
        assertEquals(listOf("A member · 22 Oct 2026", "A member · 21 Oct 2026"), private.feed.map { it.caption })
        assertTrue(private.mostSeen.isEmpty())
        assertEquals(listOf(ClubSeen(null, "Office film club")), runBlocking { service.seenInClubs(alice) }["tt0133093"])

        // Back on her defaults, Carol is named again and her days hidden.
        runBlocking { service.followDefaults(users.getById("carol"), club.id) }
        assertEquals(listOf("Carol", "Carol"), runBlocking { service.clubOf(alice, club.id) }!!.feed.map { it.caption })
    }

    @Test
    fun `a member counts once in the tags, and a friend counts as a friend`(orm: ORMTemplate) {
        val service = service(orm)
        val movies = orm.repository<MovieRepository>()
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val office = runBlocking { service.create(alice, "Office film club") }
        val thursday = runBlocking { service.create(alice, "A Thursday club") }
        orm.join(office, bob)
        orm.join(thursday, bob)
        orm.join(office, carol)
        orm.repository<FriendshipRepository>().befriend(alice, carol, Instant.now())
        val seen = orm.repository<MovieSeenRepository>()
        seen.insert(MovieSeen(bob, movies.getById("tt0068646"), Instant.now()))
        seen.insert(MovieSeen(carol, movies.getById("tt0068646"), Instant.now()))

        // Bob once, under the first of the two clubs by name; Carol is a friend, tagged as one.
        assertEquals(mapOf("tt0068646" to listOf(ClubSeen("Bob", "A Thursday club"))), runBlocking { service.seenInClubs(alice) })
    }

    @Test
    fun `a sign-in that joins two accounts moves the memberships, an admin if either was`(orm: ORMTemplate) {
        val service = service(orm)
        val members = orm.repository<ClubMemberRepository>()
        val alice = orm.insertUser("alice", name = "Alice")
        val phone = orm.insertUser("alice-phone", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val office = runBlocking { service.create(bob, "Office film club") }
        val thursday = runBlocking { service.create(phone, "Thursday movie night") }
        orm.join(office, alice)
        orm.join(office, phone)
        members.update(members.findMember(office, phone.id)!!.copy(admin = true))

        members.moveTo(phone, alice)

        assertEquals(listOf("Office film club", "Thursday movie night"), members.findClubs(alice).map { it.club.name })
        assertTrue(members.findClubs(alice).all { it.admin })
        assertTrue(members.findClubs(phone).isEmpty())
    }
}
