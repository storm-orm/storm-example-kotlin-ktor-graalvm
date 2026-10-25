package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubInvite
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.FriendInvite
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubInviteRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.InviteRepository
import st.orm.template.transaction
import java.time.Instant

/** What an invite link leads to, for the page that offers it. */
sealed interface InviteState {
    /** An invite to be friends with [inviter]. */
    data class Befriend(val inviter: User) : InviteState
    /** An invite to join [club], from [inviter]. */
    data class Join(val club: Club, val inviter: User, val memberCount: Long) : InviteState
    /** The visitor's own friend invite, to share rather than accept. */
    data object Own : InviteState
    /** An invite to a club the visitor is in already. */
    data class AlreadyMember(val club: Club) : InviteState
    /** An invite to a club that has no room left. */
    data object Full : InviteState
    /** An invite that was accepted already, expired, or never was. */
    data object Gone : InviteState
}

/** Where accepting an invite took the visitor. */
sealed interface Accepted {
    data object Friends : Accepted
    data class Joined(val club: Club) : Accepted
}

/**
 * The invite links of both kinds, behind one page: a link carries a token,
 * and the invite it reads is a [FriendInvite] or a [ClubInvite], each its
 * own type from the one table. What the page shows and what accepting does
 * follow from the type, in a `when` the compiler checks for both kinds.
 * Making the links is up to [FriendService] and [ClubService].
 */
class InviteService(
    private val inviteRepository: InviteRepository,
    private val friendInviteRepository: FriendInviteRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val clubInviteRepository: ClubInviteRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubRepository: ClubRepository,
    private val clubMemberRepository: ClubMemberRepository
) {

    /** What an invite link leads to for the visitor opening it. */
    suspend fun invite(token: String, user: User, now: Instant = Instant.now()): InviteState = transaction(readOnly = true) {
        when (val invite = inviteRepository.findOpen(token, now)) {
            null -> InviteState.Gone
            is FriendInvite -> if (invite.inviter.id == user.id) InviteState.Own else InviteState.Befriend(invite.inviter)
            is ClubInvite -> {
                val memberCount = clubMemberRepository.countMembers(invite.club)
                when {
                    clubMemberRepository.findMember(invite.club, user.id) != null -> InviteState.AlreadyMember(invite.club)
                    memberCount >= ClubService.MAX_MEMBERS -> InviteState.Full
                    else -> InviteState.Join(invite.club, invite.inviter, memberCount)
                }
            }
        }
    }

    /**
     * Accepts an invite. A friend invite makes the visitor and the inviter
     * friends; a club invite makes the visitor a member, unless the club is
     * full, counted with the club locked so its size holds. A personal
     * invite is used up, a public one stays for the next visitor. Returns
     * where that took the visitor, or null for an invite gone, one of their
     * own, or a club with no room.
     */
    suspend fun accept(token: String, user: User, now: Instant = Instant.now()): Accepted? = transaction {
        when (val invite = inviteRepository.findOpen(token, now)) {
            null -> null
            is FriendInvite -> {
                if (invite.inviter.id == user.id) return@transaction null
                friendshipRepository.befriend(user, invite.inviter, now)
                friendRequestRepository.removeBetween(user, invite.inviter)
                if (invite.reusable) friendInviteRepository.countAccepted(invite.token) else friendInviteRepository.remove(invite)
                Accepted.Friends
            }
            is ClubInvite -> {
                val club = clubRepository.lock(invite.club.id) ?: return@transaction null
                if (clubMemberRepository.findMember(club, user.id) == null) {
                    if (clubMemberRepository.countMembers(club) >= ClubService.MAX_MEMBERS) return@transaction null
                    clubMemberRepository.insert(ClubMember(club, user, admin = false, joinedAt = now))
                    if (invite.reusable) clubInviteRepository.countAccepted(invite.token) else clubInviteRepository.remove(invite)
                }
                Accepted.Joined(club)
            }
        }
    }
}
