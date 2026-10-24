package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.FriendInvite
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.entityId
import st.orm.template.transaction
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * A movie a friend saw lately, as far as the friend shares it: their name,
 * or null to show it as a friend's, and the day, or null without one.
 */
data class FriendSeenMovie(
    val movie: Movie,
    val friendName: String?,
    val friendAvatarUrl: String?,
    val seenAt: Instant?
) {
    /** The line under the card: "Alice · 14 Mar 2026", "A friend", and so on. */
    val caption: String
        get() = listOfNotNull(friendName ?: "A friend", seenAt?.let { SEEN_DAY.format(it) }).joinToString(" · ")
}

private val SEEN_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/** The friends page: the friends, what they saw lately, and what the visitor shares. */
data class FriendsView(
    val friends: List<User>,
    val feed: List<FriendSeenMovie>,
    val sharing: User
)

/** What an invite link leads to, for the page that offers it. */
sealed interface InviteState {
    /** An invite the visitor can accept, from [inviter]. */
    data class Open(val inviter: User) : InviteState
    /** The visitor's own invite, to share rather than accept. */
    data object Own : InviteState
    /** An invite that was accepted already, expired, or never was. */
    data object Gone : InviteState
}

/**
 * Friends: a signed-in visitor invites another with a link, and once the
 * other accepts, each sees what the other watched lately, as far as the
 * other shares it ([User.shareSeen], [User.shareName], [User.shareDates]).
 * There is no directory of users: an invite link is the only way to become
 * someone's friend.
 */
class FriendService(
    private val userRepository: UserRepository,
    private val friendshipRepository: FriendshipRepository,
    private val friendInviteRepository: FriendInviteRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val checkInRepository: CheckInRepository
) {
    private val random = SecureRandom()

    /** The friends page for a signed-in visitor. */
    suspend fun friendsOf(user: User): FriendsView = transaction(readOnly = true) {
        val friends = friendshipRepository.findFriends(user).map { it.friend }
        FriendsView(friends = friends, feed = feed(friends, FEED_LIMIT), sharing = user)
    }

    /** What the visitor's friends saw lately, for the home page row; empty without friends who share. */
    suspend fun latestOfFriends(user: User, limit: Int): List<FriendSeenMovie> = transaction(readOnly = true) {
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
    private fun feed(friends: List<User>, limit: Int): List<FriendSeenMovie> {
        val sharing = friends.filter { it.shareSeen }.associateBy { it.id }
        if (sharing.isEmpty()) return emptyList()
        return checkInRepository.findLatestOf(sharing.values.toList(), limit).map { checkIn ->
            val friend = sharing.getValue(checkIn.user.entityId())
            FriendSeenMovie(
                movie = checkIn.movie,
                friendName = friend.name.takeIf { friend.shareName },
                friendAvatarUrl = friend.avatarUrl.takeIf { friend.shareName },
                seenAt = checkIn.watchedAt.takeIf { friend.shareDates }
            )
        }
    }

    /**
     * A new personal invite link's token, for a signed-in visitor to send to
     * one friend. Personal invites of theirs that expired are cleared at the
     * same time.
     */
    suspend fun createInvite(user: User, now: Instant = Instant.now()): String = transaction {
        friendInviteRepository.removeExpired(user, now.minus(INVITE_VALIDITY))
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
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { random.nextBytes(it) })
        friendInviteRepository.insert(FriendInvite(token = token, inviter = user, createdAt = now, reusable = reusable))
        return token
    }

    /** What an invite link leads to for the visitor opening it. */
    suspend fun invite(token: String, user: User, now: Instant = Instant.now()): InviteState = transaction(readOnly = true) {
        val invite = openInvite(token, now)
        when {
            invite == null -> InviteState.Gone
            invite.inviter.id == user.id -> InviteState.Own
            else -> InviteState.Open(invite.inviter)
        }
    }

    /**
     * Accepts an invite: the visitor and the inviter become friends, and a
     * personal invite is used up; a public one stays for the next visitor.
     * Returns whether it was accepted; an invite of the visitor's own, or
     * one gone, is not.
     */
    suspend fun accept(token: String, user: User, now: Instant = Instant.now()): Boolean = transaction {
        val invite = openInvite(token, now)?.takeIf { it.inviter.id != user.id } ?: return@transaction false
        friendshipRepository.befriend(user, invite.inviter, now)
        if (!invite.reusable) friendInviteRepository.remove(invite)
        true
    }

    /** An invite that can still be accepted: a public one as long as it exists, a personal one for two weeks. */
    private fun openInvite(token: String, now: Instant): FriendInvite? =
        friendInviteRepository.findById(token)?.takeIf { it.reusable || it.createdAt.isAfter(now.minus(INVITE_VALIDITY)) }

    /** Ends a friendship, both ways. */
    suspend fun remove(user: User, friendId: String) = transaction {
        userRepository.findById(friendId)?.let { friendshipRepository.unfriend(user, it) }
    }

    /** What the visitor shares with friends. */
    suspend fun updateSharing(user: User, shareSeen: Boolean, shareName: Boolean, shareDates: Boolean) = transaction {
        userRepository.update(user.copy(shareSeen = shareSeen, shareName = shareName, shareDates = shareDates))
    }

    companion object {
        /** How long an invite link can be accepted. */
        val INVITE_VALIDITY: Duration = Duration.ofDays(14)
        private const val FEED_LIMIT = 36
    }
}
