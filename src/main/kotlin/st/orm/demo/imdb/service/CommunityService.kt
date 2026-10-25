package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.transaction

/**
 * The community page: the visitor's friends and clubs together. What each
 * watched lately, what the visitor has in common with everyone in it, and
 * the friends and clubs themselves with what the visitor shares, which the
 * page manages.
 */
data class CommunityView(
    val friends: FriendsView,
    val clubs: ClubsView,
    /** What the other members of the visitor's clubs saw lately. */
    val clubsSeen: List<SharedCheckIn>,
    /** What the visitor has in common with each friend and club member, the most first. */
    val inCommon: List<InCommon>
) {
    /** Whether the visitor has friends or clubs: without, the page is what manages them. */
    val hasCommunity: Boolean get() = friends.friends.isNotEmpty() || clubs.clubs.isNotEmpty()
}

/**
 * The visitor's community as one: friends and the members of their clubs.
 * A person who is both is one person, and shares what they watch as a
 * friend does, or else as a member of any club both are in.
 */
class CommunityService(
    private val friendService: FriendService,
    private val clubService: ClubService,
    private val tasteOverlap: TasteOverlap,
    private val userRepository: UserRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val movieSeenRepository: MovieSeenRepository
) {
    suspend fun overview(user: User): CommunityView {
        val friends = friendService.friendsOf(user)
        val clubs = clubService.clubsOf(user)
        return transaction(readOnly = true) {
            val sharingMembers = clubMemberRepository.findMembers(clubs.clubs.map { it.membership.club })
                .filter { it.user.id != user.id }
                .groupBy({ it.user.id }, { it.sharesSeen })
            val friendIds = friends.friends.map { it.id }.toSet()
            val members = clubMemberRepository.findMembers(clubs.clubs.map { it.membership.club })
                .map { it.user }
                .filter { it.id != user.id && it.id !in friendIds }
                .distinctBy { it.id }
            CommunityView(
                friends = friends,
                clubs = clubs,
                clubsSeen = clubService.latestInClubs(user, FEED_LIMIT),
                inCommon = tasteOverlap.inCommon(user, friends.friends + members) { other ->
                    if (other.id in friendIds) other.shareSeen else sharingMembers[other.id].orEmpty().any { it }
                }
            )
        }
    }

    /**
     * The movies the visitor and another person both watched, the other's
     * latest first, as far as the other shares what they watch: as a friend,
     * or as a member of a club both are in. Null for anyone else.
     */
    suspend fun moviesInCommon(user: User, otherId: String, limit: Int): List<Movie>? = transaction(readOnly = true) {
        val other = userRepository.findById(otherId) ?: return@transaction null
        val shares = if (friendshipRepository.areFriends(user, other)) other.shareSeen else {
            val mine = clubMemberRepository.findClubs(user).map { it.club.id }.toSet()
            clubMemberRepository.findClubs(other).any { it.club.id in mine && it.sharesSeen }
        }
        if (!shares) return@transaction null
        movieSeenRepository.findSeenInCommon(user, other, limit).map { it.movie }
    }

    private companion object {
        const val FEED_LIMIT = 36
    }
}
