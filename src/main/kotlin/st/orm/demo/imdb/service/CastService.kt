package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.CastAnswer
import st.orm.demo.imdb.model.CastBid
import st.orm.demo.imdb.model.CastRound
import st.orm.demo.imdb.model.Credit
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.GamePlayerPk
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.CastAnswerRepository
import st.orm.demo.imdb.repository.CastBidRepository
import st.orm.demo.imdb.repository.CastRoundRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.DIRECTOR
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant

/** Where a round of Total Recall is: the players bidding, the top bidder naming, or over. */
enum class CastPhase { BIDDING, NAMING, OVER }

/** A player's bid as the round shows it: how many, once the bidding is over; before that, only that they bid. */
data class CastBidView(val user: User, val count: Int?, val you: Boolean)

/** Someone in the credits, or a movie of them, as the end of a round shows it: named or not, and its page when the catalogue knows it. */
data class CastReveal(val credit: Credit, val named: Boolean, val personId: String?, val movieId: String? = null)

/**
 * A round of Total Recall for a player: the movie, where the round is,
 * the bids, who names and how many they bid ([target], none on one's own),
 * the names given so far, and, once over, the whole cast with the ones named.
 */
data class CastView(
    val match: GameMatch,
    /** The movie whose cast is the question; or the person whose movies are, in a round of a person. */
    val movie: Movie?,
    val person: Person?,
    val phase: CastPhase,
    val bids: List<CastBidView>,
    val myBid: Int?,
    val namer: User?,
    val target: Int?,
    val answers: List<CastAnswer>,
    val right: Int,
    val strikes: Int,
    val won: Boolean,
    val youName: Boolean,
    val youBid: Boolean,
    val hoursLeft: Long?,
    val creditCount: Int,
    val reveal: List<CastReveal>,
    val best: Int
) {
    val strikesLeft: Int get() = CastService.STRIKES - strikes
    /** Whether the round is of a person, their movies to name. */
    val reel: Boolean get() = person != null
    val title: String get() = "Total Recall"
    /** What is to be named: "actors and the director", or "movies". */
    val named: String get() = if (reel) "movies" else "cast"
}

/** A name given, for the page that follows the namer as they go. */
@Serializable
data class CastLiveAnswer(val answer: String, val credit: String?)

/** Where a round stands, for the page that follows the namer: it adds the names given since, and reloads when the round moves on. */
@Serializable
data class CastLive(val version: String, val phase: String, val right: Int, val strikes: Int, val answers: List<CastLiveAnswer>)

/**
 * Total Recall: the house picks a well-known movie, and the players say
 * how many of its cast they can name, the director counting too. With a
 * friend or a club everyone bids at once, in their own time: the bidding
 * closes once all have bid, or after a day. The highest bid, of equal ones
 * the first, then names them, one at a time, while the others follow along;
 * reaching the bid wins the round, and three wrong names, or a day gone by,
 * loses it. On one's own there is no bid: the player names as many as they
 * know, until three wrong names. Nobody waits on anybody: where the round
 * is, is worked out whenever it is looked at.
 *
 * The cast is TMDB's whole cast and directors, stored with the movie's TMDB
 * source; without TMDB, IMDb's top-billed and directors.
 */
class CastService(
    private val matchService: MatchService,
    private val gameMatchRepository: GameMatchRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val castRoundRepository: CastRoundRepository,
    private val castBidRepository: CastBidRepository,
    private val castAnswerRepository: CastAnswerRepository,
    private val principalRepository: PrincipalRepository,
    private val ratingRepository: RatingRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val userRepository: UserRepository,
    private val tmdbService: TmdbService,
    private val poolService: PoolService,
    /** The votes a movie needs to be picked: the house picks movies everybody knows. */
    private val minimumVotes: Int = MINIMUM_VOTES,
    /** The credits a movie needs to be picked, so there is something to bid on. */
    private val minimumCredits: Int = MINIMUM_CREDITS
) {

    /**
     * Starts a round with the opponents: a well-known movie of the host's
     * taste, with its whole cast asked of TMDB for a signed-in host; null
     * when the friend or the club is not the host's, or no movie has cast enough.
     */
    suspend fun start(host: User, opponents: Opponents, now: Instant = Instant.now(), game: Game = Game.CAST): GameMatch? {
        val rule = GameRules.of(game)
        // A movie every player watched, which every player at the level is; the host's taste when they share too few.
        val pool = transaction(readOnly = true) {
            val players = matchService.playersOf(host, opponents, rule)?.second ?: return@transaction null
            poolService.poolOf(host, players, rule.pool, minimumVotes, POOL, minimum = MINIMUM_SHARED)
        } ?: return null
        // Of a movie: one with cast enough, its credits asked outside any transaction, so no connection waits on TMDB.
        // Of a person: someone top-billed in the pool's movies with well-known movies enough to name.
        val movie = if (game == Game.CAST) pool.movies.shuffled().take(TRIES).firstOrNull { movieCredits(it, askTmdb = host.name != null, now).size >= minimumCredits } ?: return null else null
        val person = if (game == Game.REEL) transaction(readOnly = true) { personFor(pool) } ?: return null else null
        return transaction {
            val stored = userRepository.touch(host, now)
            val (club, players) = matchService.playersOf(stored, opponents, rule) ?: return@transaction null
            val (match, _) = matchService.start(
                GameMatch(game = game, mode = opponents.mode, club = club, host = stored, createdAt = now, closesAt = null, endedAt = null),
                // A club's members join the round as they bid.
                if (opponents.mode == GameMode.CLUB) listOf(stored) else players,
                now,
                pool
            )
            castRoundRepository.insert(CastRound(match, movie, person))
            match
        }
    }

    /** Someone top-billed in the pool's movies, at random among those with movies enough to name; null when nobody has. */
    private fun personFor(pool: MoviePool): Person? {
        val billed = principalRepository.findTasteCredits(pool.movies.map { it.ref() }, TOP_BILLED).map { it.person }.distinctBy { it.id }
        val known = principalRepository.countWellKnownCredits(billed.map { it.ref() }, REEL_VOTES).filter { it.credits >= minimumCredits }.map { it.person.entityId() }.toSet()
        return billed.filter { it.id in known }.randomOrNull()
    }

    /** The round for the visitor, or null when they may not look at it. */
    suspend fun roundOf(user: User, matchId: Long, now: Instant = Instant.now()): CastView? {
        val round = roundFor(user, matchId) ?: return null
        val credits = creditsOf(round, askTmdb = false, now)
        return transaction(readOnly = true) {
            val match = round.match
            val bids = castBidRepository.findBids(match)
            val answers = castAnswerRepository.findAnswers(match)
            val state = stateOf(match, bids, answers, now)
            val over = state.phase == CastPhase.OVER
            val named = answers.mapNotNull { it.credit }.toSet()
            CastView(
                match = match,
                movie = round.movie,
                person = round.person,
                phase = state.phase,
                bids = bids.map { CastBidView(it.user, it.count.takeIf { state.phase != CastPhase.BIDDING }, it.user.id == user.id) },
                myBid = bids.firstOrNull { it.user.id == user.id }?.count,
                namer = state.namer,
                target = state.target,
                answers = answers,
                right = state.right,
                strikes = state.strikes,
                won = state.won,
                youName = state.phase == CastPhase.NAMING && state.namer?.id == user.id,
                youBid = state.phase == CastPhase.BIDDING && canJoin(user, match),
                hoursLeft = state.until?.let { Duration.between(now, it).toHours().coerceAtLeast(0) },
                creditCount = credits.size,
                reveal = if (over) revealOf(round, credits, named) else emptyList(),
                best = if (match.mode == GameMode.SOLO) gamePlayerRepository.findBestSolo(user, match.game) else 0
            )
        }
    }

    /**
     * A bid: how many of the cast the visitor says they can name, at least
     * one and at most the cast. They may change it while the bidding is open;
     * a club's member joins the round with it. False when it was not taken.
     */
    suspend fun bid(user: User, matchId: Long, count: Int, now: Instant = Instant.now()): Boolean {
        val round = roundFor(user, matchId)?.takeIf { it.match.mode != GameMode.SOLO } ?: return false
        val most = creditsOf(round, askTmdb = false, now).size
        return transaction {
            val match = round.match
            val state = stateOf(match, castBidRepository.findBids(match), castAnswerRepository.findAnswers(match), now)
            if (state.phase != CastPhase.BIDDING) return@transaction false
            val player = playerOf(user, match, now) ?: return@transaction false
            castBidRepository.upsert(CastBid(match, player.user, count.coerceIn(1, most.coerceAtLeast(1)), now))
            true
        }
    }

    /**
     * A name given by the player whose turn it is to name: someone in the
     * credits, named for the first time, counts; a name that fits no one is
     * a strike. One named before, or one that fits several, costs nothing.
     * Null when it is not the visitor's to name.
     */
    suspend fun name(user: User, matchId: Long, answer: String, now: Instant = Instant.now()): NameVerdict? {
        val round = roundFor(user, matchId) ?: return null
        val credits = creditsOf(round, askTmdb = false, now)
        return transaction {
            val match = round.match
            val bids = castBidRepository.findBids(match)
            val answers = castAnswerRepository.findAnswers(match)
            val state = stateOf(match, bids, answers, now)
            if (state.phase != CastPhase.NAMING || state.namer?.id != user.id) return@transaction null
            val result = CastMatcher.match(answer, credits, answers.mapNotNull { it.credit }.toSet())
            if (result.verdict == NameVerdict.RIGHT || result.verdict == NameVerdict.WRONG) {
                val given = CastAnswer(match, answers.size, userRepository.touch(user, now), answer.trim().take(MAX_ANSWER), result.credit?.name, now)
                castAnswerRepository.insert(given)
                val after = stateOf(match, bids, answers + given, now)
                gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = user.id))?.let { player ->
                    gamePlayerRepository.update(player.copy(score = after.right, finished = after.phase == CastPhase.OVER, updatedAt = now))
                }
            }
            result.verdict
        }
    }

    /** The namer stops: the round is over as it stands, which, short of their bid, loses it. */
    suspend fun stop(user: User, matchId: Long, now: Instant = Instant.now()) {
        val round = roundFor(user, matchId) ?: return
        transaction {
            val match = round.match
            val state = stateOf(match, castBidRepository.findBids(match), castAnswerRepository.findAnswers(match), now)
            if (state.phase != CastPhase.NAMING || state.namer?.id != user.id) return@transaction
            gameMatchRepository.update(match.copy(endedAt = now))
            gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = user.id))?.let { player ->
                gamePlayerRepository.update(player.copy(finished = true, updatedAt = now))
            }
        }
    }

    /** Where the round stands for the page that follows the namer; null when the visitor may not look at it. */
    suspend fun liveOf(user: User, matchId: Long, now: Instant = Instant.now()): CastLive? = transaction(readOnly = true) {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CAST || it.game == Game.REEL } ?: return@transaction null
        val bids = castBidRepository.findBids(match)
        val answers = castAnswerRepository.findAnswers(match)
        val state = stateOf(match, bids, answers, now)
        CastLive(versionOf(match, bids, answers, state), state.phase.name, state.right, state.strikes,
            answers.map { CastLiveAnswer(it.answer, it.credit) })
    }

    /** What a round looks like at [now], as a short text that changes with every bid and every name. */
    fun versionOf(match: GameMatch, now: Instant): String {
        val bids = castBidRepository.findBids(match)
        val answers = castAnswerRepository.findAnswers(match)
        return versionOf(match, bids, answers, stateOf(match, bids, answers, now))
    }

    private fun versionOf(match: GameMatch, bids: List<CastBid>, answers: List<CastAnswer>, state: State): String =
        "${state.phase}|${bids.size}|${answers.size}|${match.endedAt != null}"

    /**
     * How a round stands, for the lists of one's games, inside their
     * transaction: whether it is over, in a line, and whether it waits on the visitor.
     */
    fun standingOf(user: User, match: GameMatch, now: Instant, bids: List<CastBid> = castBidRepository.findBids(match), answers: List<CastAnswer> = castAnswerRepository.findAnswers(match)): Triple<Boolean, String, Boolean> {
        val state = stateOf(match, bids, answers, now)
        val namer = if (state.namer?.id == user.id) "You" else state.namer?.name ?: "Someone"
        return when (state.phase) {
            CastPhase.BIDDING -> bids.firstOrNull { it.user.id == user.id }
                ?.let { Triple(false, "You bid ${it.count}; waiting for the others", false) }
                ?: Triple(false, "Your bid: how many can you name?", true)
            CastPhase.NAMING -> when {
                state.target == null -> Triple(false, "Your turn: ${state.right} named", true)
                state.namer?.id == user.id -> Triple(false, "Your turn: name ${state.target}", true)
                else -> Triple(false, "$namer is naming ${state.target}", false)
            }
            CastPhase.OVER -> Triple(true, when {
                state.target == null -> "Over: ${state.right} named"
                state.namer == null -> "Nobody bid"
                state.won -> "$namer named all ${state.target}"
                else -> "$namer named ${state.right} of ${state.target}"
            }, false)
        }
    }

    /**
     * Where a round is: on one's own, naming until three strikes or a stop;
     * with others, bidding until all have bid or a day went by, then the top
     * bid naming until it is reached, three strikes, a stop, or a day more.
     * [until] is when the bidding or the naming closes.
     */
    private class State(
        val phase: CastPhase,
        val namer: User?,
        val target: Int?,
        val until: Instant?,
        val right: Int,
        val strikes: Int,
        val won: Boolean
    )

    /** Where each of several rounds stands for the user, by the match's id, the bids and the names read in two queries. */
    fun standingsOf(user: User, matches: List<GameMatch>, now: Instant): Map<Long, Triple<Boolean, String, Boolean>> {
        if (matches.isEmpty()) return emptyMap()
        val bids = castBidRepository.findBidsOf(matches)
        val answers = castAnswerRepository.findAnswersOf(matches)
        return matches.associate { it.id to standingOf(user, it, now, bids[it.id].orEmpty(), answers[it.id].orEmpty()) }
    }

    private fun stateOf(match: GameMatch, bids: List<CastBid>, answers: List<CastAnswer>, now: Instant): State {
        val right = answers.count { it.right }
        val strikes = answers.size - right
        if (match.mode == GameMode.SOLO) {
            val over = match.endedAt != null || strikes >= STRIKES
            return State(if (over) CastPhase.OVER else CastPhase.NAMING, match.host, null, null, right, strikes, won = false)
        }
        val allIn = bids.isNotEmpty() && bids.size >= biddersOf(match)
        val biddingEnds = if (allIn) bids.maxOf { it.bidAt } else match.createdAt.plus(BIDDING)
        if (match.endedAt == null && !allIn && now.isBefore(biddingEnds)) {
            return State(CastPhase.BIDDING, null, null, biddingEnds, 0, 0, won = false)
        }
        val top = bids.firstOrNull() ?: return State(CastPhase.OVER, null, null, null, 0, 0, won = false)
        val namingEnds = biddingEnds.plus(NAMING)
        val won = right >= top.count
        val over = won || strikes >= STRIKES || match.endedAt != null || !now.isBefore(namingEnds)
        return State(if (over) CastPhase.OVER else CastPhase.NAMING, top.user, top.count, namingEnds.takeUnless { over }, right, strikes, won)
    }

    /** How many bids close the bidding early: both, with a friend; every member at the level, in a club. */
    private fun biddersOf(match: GameMatch): Int = when (match.mode) {
        GameMode.SOLO -> 1
        GameMode.FRIEND -> gamePlayerRepository.findPlayers(match).size
        GameMode.CLUB -> clubMemberRepository.findMembers(listOfNotNull(match.club)).count { matchService.mayJoin(it.user, match) }
    }

    /** Whether the visitor may bid: a player of the match, or a member of its club at the game's level. */
    private fun canJoin(user: User, match: GameMatch): Boolean =
        gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = user.id)) != null ||
            (match.mode == GameMode.CLUB && matchService.mayJoin(user, match))

    /** The visitor's player in a match; a club's member at the level joins as they bid. */
    private fun playerOf(user: User, match: GameMatch, now: Instant): GamePlayer? {
        gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = user.id))?.let { return it }
        if (match.mode != GameMode.CLUB || !matchService.mayJoin(user, match)) return null
        val player = GamePlayer(match, userRepository.touch(user, now), gamePlayerRepository.findPlayers(match).size, now)
        gamePlayerRepository.insert(player)
        return player
    }

    /** The round of a match the visitor may look at, with its movie. */
    private suspend fun roundFor(user: User, matchId: Long): CastRound? = transaction(readOnly = true) {
        matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CAST || it.game == Game.REEL }?.let(castRoundRepository::findById)
    }

    /**
     * What a round asks to be named: a movie's credits, TMDB's directors and
     * whole cast, as stored, or asked when [askTmdb], else IMDb's directors
     * and top-billed; or a person's well-known movies, each as a credit
     * named by its title, with its year where the character goes.
     */
    private suspend fun creditsOf(round: CastRound, askTmdb: Boolean, now: Instant): List<Credit> {
        round.movie?.let { return movieCredits(it, askTmdb, now) }
        val person = round.person ?: return emptyList()
        return transaction(readOnly = true) { moviesOf(person) }.map { Credit(it.primaryTitle, it.startYear?.toString(), director = false) }.distinctBy { it.name }
    }

    private suspend fun movieCredits(movie: Movie, askTmdb: Boolean, now: Instant): List<Credit> =
        tmdbService.credits(movie.id, askTmdb, now)?.people
            ?: transaction(readOnly = true) {
                principalRepository.findCast(movie)
                    .filter { it.category == DIRECTOR || it.category in ACTING_CATEGORIES }
                    .sortedBy { it.category != DIRECTOR }
                    .map { Credit(it.person.primaryName, it.characters, director = it.category == DIRECTOR) }
                    .distinctBy { it.name }
            }

    /** A person's well-known movies, the best known first. */
    private fun moviesOf(person: Person): List<Movie> {
        val movies = principalRepository.findFilmography(person).map { it.movie }.distinctBy { it.id }
        return ratingRepository.findByMovies(movies.map { it.ref() }).filter { it.voteCount >= REEL_VOTES }.sortedByDescending { it.voteCount }.map { it.movie }
    }

    /** The whole cast, or all the movies, at the end, the named first, each with its page where the catalogue has it. */
    private fun revealOf(round: CastRound, credits: List<Credit>, named: Set<String>): List<CastReveal> {
        val movie = round.movie
        if (movie != null) {
            val people = principalRepository.findCast(movie).associate { CastMatcher.normalize(it.person.primaryName) to it.person.id }
            return credits.map { CastReveal(it, it.name in named, people[CastMatcher.normalize(it.name)]) }.sortedBy { !it.named }
        }
        val movies = round.person?.let { moviesOf(it) }.orEmpty().associate { CastMatcher.normalize(it.primaryTitle) to it.id }
        return credits.map { CastReveal(it, it.name in named, null, movies[CastMatcher.normalize(it.name)]) }.sortedBy { !it.named }
    }

    companion object {
        /** The wrong names that end the naming. */
        const val STRIKES = 3
        private val BIDDING: Duration = Duration.ofHours(24)
        private val NAMING: Duration = Duration.ofHours(24)
        private const val MINIMUM_VOTES = 200_000
        private const val MINIMUM_CREDITS = 8
        /** The movies the players must share for the round to be played from them. */
        private const val MINIMUM_SHARED = 8
        /** The actors of a movie who may be the question of a round of a person: the top-billed. */
        private const val TOP_BILLED = 4
        /** The votes a movie needs to count among a person's movies to name. */
        private const val REEL_VOTES = 10_000
        private const val POOL = 300
        private const val TRIES = 5
        private const val MAX_ANSWER = 80
    }
}
