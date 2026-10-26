package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.UserRepository
import java.time.Duration
import java.time.Instant

/** Who a new match is with: on one's own, one of the host's friends, or one of their clubs. */
data class Opponents(val mode: GameMode, val friendId: String? = null, val clubId: Long? = null)

/** The people a match can be started with: the host's friends and clubs. */
data class Company(val friends: List<User>, val clubs: List<Club>)

/**
 * What the games share: who plays a match and who may look at it. A match
 * on one's own is the host's; one with a friend, the two players'; a
 * club's, its members', who join a round as they play it. Every game
 * starts its matches through here and checks access through here, inside
 * its own transactions.
 */
class MatchService(
    private val gameMatchRepository: GameMatchRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubRepository: ClubRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val userRepository: UserRepository
) {

    /** The host's friends and clubs, for the forms that start a match. */
    fun companyOf(user: User): Company =
        if (user.name == null) Company(emptyList(), emptyList())
        else Company(friendshipRepository.findFriends(user).map { it.friend }, clubMemberRepository.findClubs(user).map { it.club }.sortedBy { it.name })

    /**
     * The players of a new match, the host first: the host alone, the host
     * and a friend, or a club's members, the others in an order of chance;
     * null when the friend is no friend or the host is not in the club. A
     * club and its members come along for a club's match.
     */
    fun playersOf(host: User, opponents: Opponents): Pair<Club?, List<User>>? = when (opponents.mode) {
        GameMode.SOLO -> null to listOf(host)
        GameMode.FRIEND -> {
            val friend = opponents.friendId?.let(userRepository::findById)?.takeIf { friendshipRepository.areFriends(host, it) }
            friend?.let { null to listOf(host, it) }
        }
        GameMode.CLUB -> {
            val club = opponents.clubId?.let(clubRepository::findById)
            val members = club?.let { clubMemberRepository.findMembers(listOf(it)).map { member -> member.user } }
            if (club == null || members == null || members.none { it.id == host.id }) null
            else club to listOf(host) + members.filter { it.id != host.id }.shuffled()
        }
    }

    /** Stores a new match with its players, each in their turn. */
    fun start(match: GameMatch, players: List<User>, now: Instant): Pair<GameMatch, List<GamePlayer>> {
        val stored = gameMatchRepository.insertAndFetch(match)
        val rows = players.mapIndexed { order, user -> GamePlayer(stored, user, order, now) }
        gamePlayerRepository.insert(rows)
        return stored to rows
    }

    /** The match, if the visitor may look at it: as its player, or as a member of its club. */
    fun visibleMatch(user: User, matchId: Long): GameMatch? {
        val match = gameMatchRepository.findById(matchId) ?: return null
        return match.takeIf { canSee(user, it) }
    }

    private fun canSee(user: User, match: GameMatch): Boolean = when (match.mode) {
        GameMode.SOLO -> match.host.id == user.id
        GameMode.FRIEND -> gamePlayerRepository.findPlayers(match).any { it.user.id == user.id }
        GameMode.CLUB -> match.club?.let { clubMemberRepository.findMember(it, user.id) } != null
    }

    companion object {
        /** How long a round played in everyone's own time stays open. */
        val ROUND: Duration = Duration.ofHours(48)
    }
}
