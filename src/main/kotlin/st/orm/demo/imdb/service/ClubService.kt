package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubInvite
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.Invite
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubInviteRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieSeenCount
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.entityId
import st.orm.template.transaction
import java.time.Instant

/** A club on the clubs page: the visitor's membership, with the club, and how many members it has. */
data class ClubSummary(val membership: ClubMember, val memberCount: Int)

/** The clubs page: the visitor's clubs, and what they share in clubs unless set apart for one. */
data class ClubsView(val clubs: List<ClubSummary>, val defaults: User)

/**
 * A club's page, for one of its members: the members, admins first, where
 * the visitor stands with each as a friend, what they watched lately and
 * what most of them have seen, as far as each shares it, and the visitor's
 * own membership, which says whether they manage the club and what they
 * share in it.
 */
data class ClubView(
    val club: Club,
    val me: ClubMember,
    val members: List<ClubMember>,
    val friendStatus: Map<String, FriendStatus>,
    val feed: List<SharedCheckIn>,
    val mostSeen: List<MovieSeenCount>,
    /** The club's invite links still open, the public one and the personal ones, for its admins; none for the other members. */
    val invites: List<OpenInvite>
) {
    /** How many of the members are admins: the last one is not offered to step down. */
    val adminCount: Int get() = members.count { it.admin }

    /** Where the visitor stands with a member, for the button by their name. */
    fun statusOf(member: ClubMember): FriendStatus = friendStatus.getValue(member.user.id)
}

/** What came of leaving a club. */
enum class Leaving {
    LEFT,
    /** The last member left, so the club is gone. */
    CLOSED,
    /** The last admin cannot leave while there are members: they make another an admin first. */
    LAST_ADMIN
}

/** A club member who saw a movie, for its purple tag: their name where they share it, and the club. */
@Serializable
data class ClubSeen(val member: String?, val club: String)

/**
 * Film clubs: a signed-in visitor starts one and is its first admin; its
 * admins invite others with a link, personal or public, as friends do, and
 * accepting one is [InviteService]'s.
 * Members need not be friends. Each member sees what the others watched, as
 * far as each shares it in that club ([ClubMember.sharesSeen] and the
 * others), which is the member's own choice: their club defaults, or
 * settings for one club. A club always keeps an admin: the changes to its
 * admins and members lock the club first, and the last admin leaves only
 * as its last member, which closes it.
 */
class ClubService(
    private val userRepository: UserRepository,
    private val clubRepository: ClubRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val clubInviteRepository: ClubInviteRepository,
    private val friendshipRepository: FriendshipRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val checkInRepository: CheckInRepository
) {
    /* ── Reading ──────────────────────────────────────────────────── */

    /** The clubs page for a signed-in visitor. Clubs are small, so the members are read in one query and counted here. */
    suspend fun clubsOf(user: User): ClubsView = transaction(readOnly = true) {
        val memberships = clubMemberRepository.findClubs(user)
        val memberCounts = clubMemberRepository.findMembers(memberships.map { it.club }).groupingBy { it.club.id }.eachCount()
        ClubsView(memberships.map { ClubSummary(it, memberCounts.getValue(it.club.id)) }, defaults = user)
    }

    /**
     * A club's page for the visitor, or null when they are not a member.
     * The feed and the most seen are of the members who share what they
     * watch, the visitor too if they do: the page is about the club.
     */
    suspend fun clubOf(user: User, clubId: Long, now: Instant = Instant.now()): ClubView? = transaction(readOnly = true) {
        val club = clubRepository.findById(clubId) ?: return@transaction null
        val me = clubMemberRepository.findMember(club, user.id) ?: return@transaction null
        val members = clubMemberRepository.findMembers(listOf(club))
        val sharing = members.filter { it.sharesSeen }
        ClubView(
            club = club,
            me = me,
            members = members,
            friendStatus = friendStatus(user, members),
            feed = feed(sharing),
            mostSeen = if (sharing.size < MOST_SEEN_MINIMUM) emptyList() else movieSeenRepository.countSeenBy(sharing.map { it.user }, MOST_SEEN_MINIMUM.toLong(), MOST_SEEN_LIMIT),
            invites = if (!me.admin) emptyList() else clubInviteRepository.findOpen(club, now.minus(Invite.VALIDITY)).map { OpenInvite.of(it, now) }
        )
    }

    /** Where the visitor stands with each member: a friend, asked either way, or neither. */
    private fun friendStatus(user: User, members: List<ClubMember>): Map<String, FriendStatus> {
        val friends = friendshipRepository.findFriends(user).map { it.friend.id }.toSet()
        val (sent, received) = friendRequestRepository.findInvolving(user).partition { it.requester.id == user.id }
        val requested = sent.map { it.recipient.id }.toSet()
        val askedYou = received.map { it.requester.id }.toSet()
        return members.associate { member ->
            member.user.id to when (member.user.id) {
                user.id -> FriendStatus.YOU
                in friends -> FriendStatus.FRIENDS
                in askedYou -> FriendStatus.ASKED_YOU
                in requested -> FriendStatus.REQUESTED
                else -> FriendStatus.NONE
            }
        }
    }

    /** The latest check-ins of the members who share what they watch, each shown as far as its member shares their name and the day. */
    private fun feed(sharing: List<ClubMember>): List<SharedCheckIn> {
        if (sharing.isEmpty()) return emptyList()
        val membersById = sharing.associateBy { it.user.id }
        return checkInRepository.findLatestOf(sharing.map { it.user }, FEED_LIMIT).map { checkIn ->
            val member = membersById.getValue(checkIn.user.entityId())
            SharedCheckIn(
                movie = checkIn.movie,
                name = member.user.name.takeIf { member.sharesName },
                avatarUrl = member.user.avatarUrl.takeIf { member.sharesName },
                seenAt = checkIn.watchedAt.takeIf { member.sharesDates },
                unnamed = "A member"
            )
        }
    }

    /**
     * The movies the visitor's fellow club members have seen, by id, each
     * with the members who did, for the purple tags. Only the members who
     * share what they watch in that club count. A member in several of the
     * visitor's clubs counts once, under the first of those clubs by name,
     * and a friend who shares with friends counts as a friend, not here.
     */
    suspend fun seenInClubs(user: User): Map<String, List<ClubSeen>> = transaction(readOnly = true) {
        val clubs = clubMemberRepository.findClubs(user).map { it.club }
        if (clubs.isEmpty()) return@transaction emptyMap()
        val friends = friendshipRepository.findFriends(user).filter { it.friend.shareSeen }.map { it.friend.id }.toSet()
        val sharing = clubMemberRepository.findMembers(clubs)
            .filter { it.sharesSeen && it.user.id != user.id && it.user.id !in friends }
            .sortedBy { it.club.name }
            .distinctBy { it.user.id }
            .associateBy { it.user.id }
        if (sharing.isEmpty()) return@transaction emptyMap()
        movieSeenRepository.findSeenBy(sharing.values.map { it.user }).groupBy(
            { it.movie.entityId() },
            { sharing.getValue(it.user.entityId()).let { member -> ClubSeen(member.user.name.takeIf { member.sharesName }, member.club.name) } }
        )
    }

    /* ── Clubs and members ────────────────────────────────────────── */

    /** Starts a club, with the visitor as its first member and admin. */
    suspend fun create(user: User, name: String, now: Instant = Instant.now()): Club = transaction {
        val club = clubRepository.insertAndFetch(Club(name = name.trim().take(NAME_LENGTH), createdAt = now))
        clubMemberRepository.insert(ClubMember(club, user, admin = true, joinedAt = now))
        club
    }

    /** Renames a club; for its admins. */
    suspend fun rename(user: User, clubId: Long, name: String) = transaction {
        administeredBy(user, clubRepository.findById(clubId))?.let { clubRepository.update(it.copy(name = name.trim().take(NAME_LENGTH))) }
    }

    /**
     * Makes a member an admin, or no longer one; for the club's admins, and
     * an admin may step down too. Returns false when that would leave the
     * club without an admin. The club is locked first, so two admins
     * stepping down at once do not both see the other remain.
     */
    suspend fun setAdmin(user: User, clubId: Long, memberId: String, admin: Boolean): Boolean = transaction {
        val club = administeredBy(user, clubRepository.lock(clubId)) ?: return@transaction false
        val member = clubMemberRepository.findMember(club, memberId) ?: return@transaction false
        if (member.admin && !admin && clubMemberRepository.countAdmins(club) <= 1) return@transaction false
        clubMemberRepository.update(member.copy(admin = admin))
        true
    }

    /** Removes another member from the club; for its admins. Leaving oneself is [leave]. */
    suspend fun remove(user: User, clubId: Long, memberId: String) = transaction {
        val club = administeredBy(user, clubRepository.lock(clubId)) ?: return@transaction
        if (memberId != user.id) clubMemberRepository.findMember(club, memberId)?.let { removeMember(it) }
    }

    /** Removes a membership, and the friend requests sent from the club to or by the member. */
    private fun removeMember(member: ClubMember) {
        clubMemberRepository.remove(member)
        friendRequestRepository.removeFrom(member.club, member.user)
    }

    /**
     * Leaves a club. The last member leaving closes it, its invites and friend requests with it;
     * the last admin does not leave while others remain, but makes one of
     * them an admin first.
     */
    suspend fun leave(user: User, clubId: Long): Leaving = transaction {
        val club = clubRepository.lock(clubId) ?: return@transaction Leaving.LEFT
        val me = clubMemberRepository.findMember(club, user.id) ?: return@transaction Leaving.LEFT
        when {
            clubMemberRepository.countMembers(club) == 1L -> {
                clubRepository.remove(club)
                Leaving.CLOSED
            }
            me.admin && clubMemberRepository.countAdmins(club) == 1L -> Leaving.LAST_ADMIN
            else -> {
                removeMember(me)
                Leaving.LEFT
            }
        }
    }

    /** The club, if the visitor is one of its admins. */
    private fun administeredBy(user: User, club: Club?): Club? =
        club?.takeIf { clubMemberRepository.findMember(it, user.id)?.admin == true }

    /* ── Sharing ──────────────────────────────────────────────────── */

    /** What the visitor shares in one club, set apart from their club defaults. */
    suspend fun updateSharing(user: User, clubId: Long, shareSeen: Boolean, shareName: Boolean, shareDates: Boolean) = transaction {
        membership(user, clubId)?.let { clubMemberRepository.update(it.copy(shareSeen = shareSeen, shareName = shareName, shareDates = shareDates)) }
    }

    /** Lets one club follow the visitor's club defaults again. */
    suspend fun followDefaults(user: User, clubId: Long) = transaction {
        membership(user, clubId)?.let { clubMemberRepository.update(it.copy(shareSeen = null, shareName = null, shareDates = null)) }
    }

    /** What the visitor shares in every club they have not set apart. */
    suspend fun updateDefaults(user: User, shareSeen: Boolean, shareName: Boolean, shareDates: Boolean) = transaction {
        userRepository.update(user.copy(clubShareSeen = shareSeen, clubShareName = shareName, clubShareDates = shareDates))
    }

    private fun membership(user: User, clubId: Long): ClubMember? =
        clubRepository.findById(clubId)?.let { clubMemberRepository.findMember(it, user.id) }

    /* ── Invites ──────────────────────────────────────────────────── */

    /**
     * A new personal invite link's token, for an admin to send to one
     * person, or null for a visitor who is not one of the club's admins. The
     * club's personal invites that expired are cleared at the same time.
     */
    suspend fun createInvite(user: User, clubId: Long, now: Instant = Instant.now()): String? = transaction {
        val club = administeredBy(user, clubRepository.findById(clubId)) ?: return@transaction null
        clubInviteRepository.removeExpired(club, now.minus(Invite.VALIDITY))
        newInvite(club, user, now, reusable = false)
    }

    /** The token of the club's public invite link, made the first time an admin asks for it. */
    suspend fun publicInvite(user: User, clubId: Long, now: Instant = Instant.now()): String? = transaction {
        val club = administeredBy(user, clubRepository.findById(clubId)) ?: return@transaction null
        clubInviteRepository.findPublic(club)?.token ?: newInvite(club, user, now, reusable = true)
    }

    /** Withdraws one of the club's invite links, public or personal, for an admin: it stops working. */
    suspend fun withdrawInvite(user: User, clubId: Long, token: String) = transaction {
        administeredBy(user, clubRepository.findById(clubId))?.let { clubInviteRepository.removeInvite(it, token) }
    }

    /** Replaces the club's public invite link with a new one: the old link stops working. */
    suspend fun replacePublicInvite(user: User, clubId: Long, now: Instant = Instant.now()): String? = transaction {
        val club = administeredBy(user, clubRepository.findById(clubId)) ?: return@transaction null
        clubInviteRepository.removePublic(club)
        newInvite(club, user, now, reusable = true)
    }

    private fun newInvite(club: Club, inviter: User, now: Instant, reusable: Boolean): String {
        val token = Invite.newToken()
        clubInviteRepository.insert(ClubInvite(token = token, club = club, inviter = inviter, createdAt = now, reusable = reusable))
        return token
    }

    companion object {
        /** The most members a club has: the tags read what all members of the visitor's clubs have seen. */
        const val MAX_MEMBERS = 100
        const val NAME_LENGTH = 60
        private const val FEED_LIMIT = 36
        private const val MOST_SEEN_LIMIT = 12

        /** How many members must have seen a movie for it to be among the club's most seen. */
        private const val MOST_SEEN_MINIMUM = 2
    }
}
