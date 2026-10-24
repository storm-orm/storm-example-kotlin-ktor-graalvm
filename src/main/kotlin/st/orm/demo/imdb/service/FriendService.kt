package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.FriendInvite
import st.orm.demo.imdb.model.FriendRequest
import st.orm.demo.imdb.model.Invite
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.entityId
import st.orm.template.transaction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A check-in of a friend or a club member, as far as they share it: their
 * name, or null to show it as [unnamed] ("A friend", "A member"), and the
 * day, or null without one.
 */
data class SharedCheckIn(
    val movie: Movie,
    val name: String?,
    val avatarUrl: String?,
    val seenAt: Instant?,
    val unnamed: String
) {
    /** The line under the card: "Alice · 14 Mar 2026", "A friend", and so on. */
    val caption: String
        get() = listOfNotNull(name ?: unnamed, seenAt?.let { SEEN_DAY.format(it) }).joinToString(" · ")
}

private val SEEN_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/**
 * The friends page: the friend requests to answer and those sent, the
 * friends, what they saw lately, and what the visitor shares.
 */
data class FriendsView(
    val requests: List<FriendRequest>,
    val sent: List<FriendRequest>,
    val friends: List<User>,
    val feed: List<SharedCheckIn>,
    val sharing: User
)

/** Where the visitor stands with another member of a club, for the button by their name. */
enum class FriendStatus {
    /** The visitor themselves. */
    YOU,
    FRIENDS,
    /** The visitor asked them, and they have not accepted, or said not now, which the visitor is not told. */
    REQUESTED,
    /** They asked the visitor. */
    ASKED_YOU,
    NONE
}

/**
 * Friends: a signed-in visitor invites another with a link, or asks a
 * member of a club they share, and once the other accepts, each sees what
 * the other watched lately, as far as the other shares it
 * ([User.shareSeen], [User.shareName], [User.shareDates]). There is no
 * directory of users: a link, or a club both are in, is the only way to
 * become someone's friend. This service makes the links and handles the
 * requests; accepting a link is [InviteService]'s, behind the page of both
 * kinds of invite. Nobody is told of a "not now" or of a friendship ended.
 */
class FriendService(
    private val userRepository: UserRepository,
    private val friendshipRepository: FriendshipRepository,
    private val friendInviteRepository: FriendInviteRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val clubRepository: ClubRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val checkInRepository: CheckInRepository
) {
    /** The friends page for a signed-in visitor. */
    suspend fun friendsOf(user: User): FriendsView = transaction(readOnly = true) {
        val friends = friendshipRepository.findFriends(user).map { it.friend }
        FriendsView(
            requests = friendRequestRepository.findOpenFor(user),
            sent = friendRequestRepository.findSentBy(user),
            friends = friends,
            feed = feed(friends, FEED_LIMIT),
            sharing = user
        )
    }

    /** What the visitor's friends saw lately, for the home page row; empty without friends who share. */
    suspend fun latestOfFriends(user: User, limit: Int): List<SharedCheckIn> = transaction(readOnly = true) {
        feed(friendshipRepository.findFriends(user).map { it.friend }, limit)
    }

    /**
     * The movies the visitor's friends have seen, by id, each with the
     * friends who did: their name where they share it, null where they show
     * as a friend. Only the friends who share what they watch count, and
     * history counts too: the marker says a friend saw it, not when.
     */
    suspend fun seenByFriends(user: User): Map<String, List<String?>> = transaction(readOnly = true) {
        val sharing = friendshipRepository.findFriends(user).map { it.friend }.filter { it.shareSeen }.associateBy { it.id }
        if (sharing.isEmpty()) return@transaction emptyMap()
        movieSeenRepository.findSeenBy(sharing.values.toList()).groupBy(
            { it.movie.entityId() },
            { sharing.getValue(it.user.entityId()).let { friend -> friend.name.takeIf { friend.shareName } } }
        )
    }

    /**
     * The latest check-ins of the friends who share what they watch, across
     * all of them, each shown as far as its friend shares their name and
     * the day.
     */
    private fun feed(friends: List<User>, limit: Int): List<SharedCheckIn> {
        val sharing = friends.filter { it.shareSeen }.associateBy { it.id }
        if (sharing.isEmpty()) return emptyList()
        return checkInRepository.findLatestOf(sharing.values.toList(), limit).map { checkIn ->
            val friend = sharing.getValue(checkIn.user.entityId())
            SharedCheckIn(
                movie = checkIn.movie,
                name = friend.name.takeIf { friend.shareName },
                avatarUrl = friend.avatarUrl.takeIf { friend.shareName },
                seenAt = checkIn.watchedAt.takeIf { friend.shareDates },
                unnamed = "A friend"
            )
        }
    }

    /**
     * A new personal invite link's token, for a signed-in visitor to send to
     * one friend. Personal invites of theirs that expired are cleared at the
     * same time.
     */
    suspend fun createInvite(user: User, now: Instant = Instant.now()): String = transaction {
        friendInviteRepository.removeExpired(user, now.minus(Invite.VALIDITY))
        newInvite(user, now, reusable = false)
    }

    /**
     * The token of the visitor's public invite link, made the first time it
     * is asked for: one link to post anywhere, which everyone who accepts it
     * follows to become a friend.
     */
    suspend fun publicInvite(user: User, now: Instant = Instant.now()): String = transaction {
        friendInviteRepository.findPublic(user)?.token ?: newInvite(user, now, reusable = true)
    }

    /** Replaces the visitor's public invite link with a new one: the old link stops working. */
    suspend fun replacePublicInvite(user: User, now: Instant = Instant.now()): String = transaction {
        friendInviteRepository.removePublic(user)
        newInvite(user, now, reusable = true)
    }

    private fun newInvite(user: User, now: Instant, reusable: Boolean): String {
        val token = Invite.newToken()
        friendInviteRepository.insert(FriendInvite(token = token, inviter = user, createdAt = now, reusable = reusable))
        return token
    }

    /* ── Requests ─────────────────────────────────────────────────── */

    /**
     * Asks another member of a club to be friends. Both must be in the club,
     * and not friends already. When the other asked the visitor already, the
     * two become friends at once: there is nothing left to accept.
     */
    suspend fun request(user: User, otherId: String, clubId: Long, now: Instant = Instant.now()) = transaction {
        val other = userRepository.findById(otherId)?.takeIf { it.id != user.id } ?: return@transaction
        val club = clubRepository.findById(clubId) ?: return@transaction
        if (clubMemberRepository.findMember(club, user.id) == null || clubMemberRepository.findMember(club, other.id) == null) return@transaction
        when {
            friendshipRepository.areFriends(user, other) -> Unit
            friendRequestRepository.findRequest(other, user) != null -> befriend(user, other, now)
            friendRequestRepository.findRequest(user, other) == null -> friendRequestRepository.insert(FriendRequest(user, other, club, now))
        }
    }

    /** Accepts a request: the two are friends from now. */
    suspend fun accept(user: User, requesterId: String, now: Instant = Instant.now()) = transaction {
        val requester = userRepository.findById(requesterId) ?: return@transaction
        if (friendRequestRepository.findRequest(requester, user) != null) befriend(user, requester, now)
    }

    /** "Not now": the request leaves the visitor's list, and the requester still sees it as sent. */
    suspend fun dismiss(user: User, requesterId: String) = transaction {
        val requester = userRepository.findById(requesterId) ?: return@transaction
        friendRequestRepository.findRequest(requester, user)?.let { friendRequestRepository.update(it.copy(dismissed = true)) }
    }

    /** Withdraws a request the visitor sent. */
    suspend fun withdraw(user: User, recipientId: String) = transaction {
        val recipient = userRepository.findById(recipientId) ?: return@transaction
        friendRequestRepository.findRequest(user, recipient)?.let { friendRequestRepository.remove(it) }
    }

    /** How many requests the visitor has to answer, for the badge on the Community tab. */
    suspend fun countRequests(user: User): Long = transaction(readOnly = true) {
        friendRequestRepository.countOpenFor(user)
    }

    private fun befriend(user: User, other: User, now: Instant) {
        friendshipRepository.befriend(user, other, now)
        friendRequestRepository.removeBetween(user, other)
    }

    /** Ends a friendship, both ways; the other is not told. */
    suspend fun remove(user: User, friendId: String) = transaction {
        userRepository.findById(friendId)?.let { friendshipRepository.unfriend(user, it) }
    }

    /** What the visitor shares with friends. */
    suspend fun updateSharing(user: User, shareSeen: Boolean, shareName: Boolean, shareDates: Boolean) = transaction {
        userRepository.update(user.copy(shareSeen = shareSeen, shareName = shareName, shareDates = shareDates))
    }

    companion object {
        private const val FEED_LIMIT = 36
    }
}
