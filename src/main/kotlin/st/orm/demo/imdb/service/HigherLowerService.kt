package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameCard
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.GamePlayerPk
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.GameCardRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * A Higher or Lower match for a player: the two movies to compare, the
 * first with its rating and the second without, until they answer; their
 * streak, and the others' as far as they played; the movie they missed on,
 * with its rating, once their game is over.
 */
data class HigherLowerView(
    val match: GameMatch,
    val me: GamePlayer?,
    val current: GameCard?,
    val next: GameCard?,
    /** The pair they missed on, the rating of the second shown, once their game is over. */
    val missed: Pair<GameCard, GameCard>?,
    val players: List<GamePlayer>,
    val best: Int,
    val cardCount: Int,
    val over: Boolean,
    /** How many hours the round has left, for a round with others; null on one's own. */
    val hoursLeft: Long?
) {
    val streak: Int get() = me?.score ?: 0
    val finished: Boolean get() = me?.finished == true
    /** Whether the visitor can answer: their game and the round still on. */
    val playing: Boolean get() = !finished && !over && current != null && next != null
    /** Whether they got through every pair. */
    val perfect: Boolean get() = finished && missed == null
}

/**
 * Higher or Lower: two posters, the first with its IMDb rating; is the
 * second rated higher or lower? A right answer moves on, the second movie
 * becoming the first; a wrong one ends the game, the streak its score.
 * Every player of a match gets the same movies in the same order
 * ([GameCard]), dealt from the best-known movies the host's movie filter
 * lets through, never two in a row with the same rating. On one's own the
 * deck is long and the best streak is the one to beat; with a friend or a
 * club it is a round of [ROUND_CARDS] that closes after two days, whoever
 * played.
 */
class HigherLowerService(
    private val matchService: MatchService,
    private val gamePlayerRepository: GamePlayerRepository,
    private val gameCardRepository: GameCardRepository,
    private val ratingRepository: RatingRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val userRepository: UserRepository,
    private val poolService: PoolService
) {

    /** Starts a match with the opponents, dealing its deck; null when the friend or the club is not the host's. */
    suspend fun start(host: User, opponents: Opponents, now: Instant = Instant.now()): GameMatch? = transaction {
        val stored = userRepository.touch(host, now)
        val (club, players) = matchService.playersOf(stored, opponents) ?: return@transaction null
        val solo = opponents.mode == GameMode.SOLO
        // Dealt from what the players watched between them; a club's members join the round as they play it.
        val pool = poolService.poolOf(stored, players, GameRules.of(Game.HIGHER_LOWER).pool, MINIMUM_VOTES, POOL)
        val (match, _) = matchService.start(
            GameMatch(game = Game.HIGHER_LOWER, mode = opponents.mode, club = club, host = stored, createdAt = now,
                closesAt = if (solo) null else now.plus(MatchService.ROUND), endedAt = null),
            if (opponents.mode == GameMode.CLUB) listOf(stored) else players,
            now,
            pool
        )
        gameCardRepository.insert(deal(pool, if (solo) SOLO_CARDS else ROUND_CARDS, Random(now.toEpochMilli()))
            .mapIndexed { position, rating -> GameCard(match, position, rating.movie, rating.averageRating) })
        match
    }

    /** A deck from the pool, shuffled, no rating twice in a row. */
    private fun deal(pool: MoviePool, size: Int, random: Random) =
        pool.ratings
            .shuffled(random)
            .fold(mutableListOf<Rating>()) { deck, rating ->
                if (deck.size < size && deck.lastOrNull()?.averageRating?.compareTo(rating.averageRating) != 0) deck += rating
                deck
            }

    /** The match for the visitor, or null when they may not look at it. */
    suspend fun matchOf(user: User, matchId: Long, now: Instant = Instant.now()): HigherLowerView? = transaction(readOnly = true) {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.HIGHER_LOWER } ?: return@transaction null
        val cards = gameCardRepository.findCards(match)
        val players = gamePlayerRepository.findPlayers(match).sortedByDescending { it.score }
        val me = players.firstOrNull { it.user.id == user.id }
        val at = me?.score ?: 0
        HigherLowerView(
            match = match,
            me = me,
            current = cards.getOrNull(at),
            next = cards.getOrNull(at + 1),
            missed = if (me?.finished == true && at + 1 < cards.size) cards[at] to cards[at + 1] else null,
            players = players,
            best = if (match.mode == GameMode.SOLO) gamePlayerRepository.findBestSolo(user, Game.HIGHER_LOWER) else 0,
            cardCount = cards.size,
            over = match.isOver(now),
            hoursLeft = match.closesAt?.let { Duration.between(now, it).toHours().coerceAtLeast(0) }
        )
    }

    /**
     * Answers whether the next movie is rated [higher] than the current
     * one: a right answer adds to the streak, a wrong one, or the last pair,
     * ends the game. A club's member joins the round with their first
     * answer. Nothing once the game or the round is over.
     */
    suspend fun answer(user: User, matchId: Long, higher: Boolean, now: Instant = Instant.now()) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.HIGHER_LOWER && !it.isOver(now) } ?: return@transaction
        val player = gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = user.id))
            ?: joinClubRound(user, match, now)
            ?: return@transaction
        if (player.finished) return@transaction
        val cards = gameCardRepository.findCards(match)
        val current = cards.getOrNull(player.score) ?: return@transaction
        val next = cards.getOrNull(player.score + 1) ?: return@transaction
        val right = (next.rating > current.rating) == higher
        val score = if (right) player.score + 1 else player.score
        gamePlayerRepository.update(player.copy(score = score, finished = !right || score + 1 >= cards.size, updatedAt = now))
    }

    /** A member of the club joining its round: a player of their own, after the ones already in. */
    private fun joinClubRound(user: User, match: GameMatch, now: Instant): GamePlayer? {
        val club = match.club?.takeIf { match.mode == GameMode.CLUB } ?: return null
        clubMemberRepository.findMember(club, user.id) ?: return null
        val player = GamePlayer(match, userRepository.touch(user, now), gamePlayerRepository.findPlayers(match).size, now)
        gamePlayerRepository.insert(player)
        return player
    }

    companion object {
        /** A round with a friend or a club: the same pairs for everyone. */
        const val ROUND_CARDS = 25
        /** On one's own the deck is long enough for any streak. */
        private const val SOLO_CARDS = 120
        /** The movies dealt from: the best known of the host's taste, none with fewer votes. */
        private const val POOL = 600
        private const val MINIMUM_VOTES = 25_000
    }
}
