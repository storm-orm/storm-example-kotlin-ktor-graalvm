package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Invite
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubInviteRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.InviteAcceptanceRepository
import st.orm.demo.imdb.repository.InviteRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.Accepted
import st.orm.demo.imdb.service.ClubService
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.InviteService
import st.orm.demo.imdb.service.InviteState
import st.orm.demo.imdb.service.TasteOverlap
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Duration
import java.time.Instant

/**
 * Invites of both kinds, in the one invite table. Storm reads them as a
 * sealed Single-Table entity with foreign keys, and queries one kind
 * through its own repository: storm-framework#754, until which these wait.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class InviteServiceTest {

    private fun inviteService(orm: ORMTemplate) = InviteService(
        orm.repository<InviteRepository>(),
        orm.repository<FriendInviteRepository>(),
        orm.repository<FriendRequestRepository>(),
        orm.repository<ClubInviteRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<ClubRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<InviteAcceptanceRepository>()
    )

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

    @Test
    fun `a friend invite, once accepted, makes two friends, and is used up`(orm: ORMTemplate) {
        val invites = inviteService(orm)
        val friends = friendService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val token = runBlocking { friends.createInvite(alice) }

        assertEquals(InviteState.Own, runBlocking { invites.invite(token, alice) })
        assertEquals(InviteState.Befriend(alice), runBlocking { invites.invite(token, bob) })
        assertNull(runBlocking { invites.accept(token, alice) })
        assertEquals(Accepted.Friends, runBlocking { invites.accept(token, bob) })

        assertEquals(listOf("Bob"), runBlocking { friends.friendsOf(alice) }.friends.map { it.name })
        assertEquals(listOf("Alice"), runBlocking { friends.friendsOf(bob) }.friends.map { it.name })
        assertEquals(InviteState.Gone, runBlocking { invites.invite(token, bob) })
        // The invite is gone, but that Bob came through Alice is kept: the levels count it.
        assertEquals(1, runBlocking { levelService(orm).progressOf(alice) }.invited)
        assertEquals(0, runBlocking { levelService(orm).progressOf(bob) }.invited)
    }

    @Test
    fun `a public friend link befriends everyone who accepts it, until it is replaced`(orm: ORMTemplate) {
        val invites = inviteService(orm)
        val friends = friendService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val dave = orm.insertUser("dave", name = "Dave")
        val link = runBlocking { friends.publicInvite(alice) }
        // Asked again, it is the same link; a year on it still works.
        assertEquals(link, runBlocking { friends.publicInvite(alice) })
        val later = Instant.now().plus(Duration.ofDays(365))

        assertEquals(Accepted.Friends, runBlocking { invites.accept(link, bob) })
        assertEquals(Accepted.Friends, runBlocking { invites.accept(link, carol, now = later) })
        assertEquals(listOf("Bob", "Carol"), runBlocking { friends.friendsOf(alice) }.friends.map { it.name })
        // The link counts how often it was accepted, for the one who shares it.
        assertEquals(2, runBlocking { friends.friendsOf(alice) }.invites.single { it.public }.accepted)

        // Replaced, the old link stops working.
        val replaced = runBlocking { friends.replacePublicInvite(alice) }
        assertEquals(InviteState.Gone, runBlocking { invites.invite(link, dave) })
        assertEquals(InviteState.Befriend(alice), runBlocking { invites.invite(replaced, dave) })
    }

    @Test
    fun `a personal invite expires after two weeks`(orm: ORMTemplate) {
        val invites = inviteService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val created = Instant.parse("2026-10-01T10:00:00Z")
        val token = runBlocking { friendService(orm).createInvite(alice, now = created) }
        val expired = created.plus(Invite.VALIDITY).plusSeconds(1)

        assertEquals(InviteState.Gone, runBlocking { invites.invite(token, bob, now = expired) })
        assertNull(runBlocking { invites.accept(token, bob, now = expired) })
    }

    @Test
    fun `only admins invite to a club, and an invite makes a member`(orm: ORMTemplate) {
        val invites = inviteService(orm)
        val clubs = clubService(orm)
        val alice = orm.insertUser("alice", name = "Alice")
        val bob = orm.insertUser("bob", name = "Bob")
        val carol = orm.insertUser("carol", name = "Carol")
        val club = runBlocking { clubs.create(alice, "Office film club") }
        val token = runBlocking { clubs.createInvite(alice, club.id) }!!

        assertEquals(InviteState.Join(club, alice, 1), runBlocking { invites.invite(token, bob) })
        assertEquals(Accepted.Joined(club), runBlocking { invites.accept(token, bob) })
        assertEquals(InviteState.Gone, runBlocking { invites.invite(token, carol) })
        // A member who is no admin makes no links.
        assertNull(runBlocking { clubs.createInvite(bob, club.id) })
        assertNull(runBlocking { clubs.publicInvite(bob, club.id) })

        // The public link: anyone joins, until an admin replaces it; a member opening it is told so.
        val link = runBlocking { clubs.publicInvite(alice, club.id) }!!
        assertEquals(InviteState.AlreadyMember(club), runBlocking { invites.invite(link, bob) })
        assertEquals(Accepted.Joined(club), runBlocking { invites.accept(link, carol) })
        assertEquals(3, runBlocking { clubs.clubOf(alice, club.id) }!!.members.size)
        runBlocking { clubs.replacePublicInvite(alice, club.id) }
        assertEquals(InviteState.Gone, runBlocking { invites.invite(link, orm.insertUser("dave", name = "Dave")) })
    }

    @Test
    fun `the invite table holds both kinds, each read as its own type`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice", name = "Alice")
        val friendToken = runBlocking { friendService(orm).createInvite(alice) }
        val club = runBlocking { clubService(orm).create(alice, "Office film club") }
        val clubToken = runBlocking { clubService(orm).createInvite(alice, club.id) }!!

        val invites = orm.repository<InviteRepository>()
        assertEquals(setOf(friendToken, clubToken), invites.findAll().map { it.token }.toSet())
        // Each kind's repository sees its own rows only.
        assertEquals(listOf(friendToken), orm.repository<FriendInviteRepository>().findAll().map { it.token })
        assertEquals(listOf(clubToken), orm.repository<ClubInviteRepository>().findAll().map { it.token })
    }
}
