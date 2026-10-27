package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.NightMethod
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.MovieNightRepository
import st.orm.demo.imdb.repository.MovieNightVoteRepository
import st.orm.demo.imdb.repository.SwipeRoundSwipeRepository
import st.orm.template.entityId
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** What waits on the visitor: games where it is their move, and the company's requests and nights to answer. */
data class Attention(val games: Int, val community: Int)

/**
 * What waits on a visitor, for the counts on the menu: the games waiting on
 * their move, and the friend requests and the nights of their clubs they
 * have not answered. Worked out now and then per visitor and kept a short
 * while, as the menu is on every page; the Play page, which works the games
 * out anyway, sets the count as it goes.
 */
class AttentionService(
    private val playService: PlayService,
    private val friendRequestRepository: FriendRequestRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val movieNightRepository: MovieNightRepository,
    private val movieNightVoteRepository: MovieNightVoteRepository,
    private val gameMatchRepository: GameMatchRepository,
    private val swipeRoundSwipeRepository: SwipeRoundSwipeRepository
) {
    private class Kept(val at: Instant, val attention: Attention)

    private val kept = ConcurrentHashMap<String, Kept>()

    /** What waits on the visitor, as of the last little while. */
    suspend fun attentionOf(user: User, now: Instant = Instant.now()): Attention {
        if (user.name == null) return Attention(0, 0)
        kept[user.id]?.takeIf { Duration.between(it.at, now) < KEPT }?.let { return it.attention }
        if (kept.size > MOST_KEPT) kept.entries.removeIf { Duration.between(it.value.at, now) >= KEPT }
        val games = playService.playOf(user, now).games.count { it.yourMove }
        return remember(user, Attention(games, communityOf(user, now)), now)
    }

    /** The Play page worked the games out: the count follows at once. */
    suspend fun noteGames(user: User, games: Int, now: Instant = Instant.now()): Attention =
        remember(user, Attention(games, communityOf(user, now)), now)

    private fun remember(user: User, attention: Attention, now: Instant): Attention {
        kept[user.id] = Kept(now, attention)
        return attention
    }

    /** The friend requests to answer, and the nights of the visitor's clubs still open that they have not voted or swiped in. */
    private suspend fun communityOf(user: User, now: Instant): Int = transaction(readOnly = true) {
        val requests = friendRequestRepository.countOpenFor(user).toInt()
        val clubs = clubMemberRepository.findClubs(user).map { it.club }
        val nights = movieNightRepository.findOpenOf(clubs, LocalDate.ofInstant(now, ZoneOffset.UTC))
        val voted = movieNightVoteRepository.findByUser(user, nights).map { it.night.entityId() }.toSet()
        val unanswered = nights.count { night ->
            when (night.method) {
                NightMethod.VOTE -> night.id !in voted
                NightMethod.SWIPE -> night.swipeMatch?.let { gameMatchRepository.findById(it.entityId()) }
                    ?.let { match -> swipeRoundSwipeRepository.findSwipes(match).none { it.user.entityId() == user.id } } ?: false
            }
        }
        requests + unanswered
    }

    private companion object {
        val KEPT: Duration = Duration.ofMinutes(2)
        const val MOST_KEPT = 1_000
    }
}
