package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.ChainLink
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.BRUCE_WILLIS
import st.orm.demo.imdb.model.JACK_NICHOLSON
import st.orm.demo.imdb.model.KEVIN_BACON
import st.orm.demo.imdb.model.MERYL_STREEP
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.ChainLinkRepository
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.entityId
import st.orm.template.refById
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random

/** Whose turn it is in a chain, and until when; a turn not taken by then passes to the next player. */
data class Turn(val player: GamePlayer, val until: Instant, val skipped: Int, val hoursLeft: Long)

/** A way a chain could have gone on: a movie, and the actor it shares with the chain's last movie. */
data class ChainAnswer(val movie: Movie, val actor: Person)

/**
 * A Movie Chain for a player: its movies so far, the players with their
 * points and hints, whose turn it is, the actors of the last movie the
 * visitor took as hints on their turn, whether there are more to take,
 * and, once over, the best-known ways the chain could have gone on.
 */
data class ChainView(
    val match: GameMatch,
    val links: List<ChainLink>,
    val players: List<GamePlayer>,
    val turn: Turn?,
    val yourTurn: Boolean,
    val over: Boolean,
    val revealed: List<Person>,
    val moreHints: Boolean,
    val answers: List<ChainAnswer>
) {
    val last: ChainLink get() = links.last()
    /** The players by their points, the most first, and of the same points the fewest hints: the board, and once over, the winner first. */
    val standings: List<GamePlayer> get() = players.sortedWith(compareByDescending<GamePlayer> { it.score }.thenBy { it.hints })
    /** Whether the house is in the game, on one's own: it answers each movie with one of its own. */
    val againstTheHouse: Boolean get() = match.mode == GameMode.SOLO
    /** The house's points, on one's own: its links. */
    val housePoints: Int get() = links.drop(1).filter { it.player == null }.sumOf { it.points }
}

/** What came of a move. */
enum class ChainMove { ADDED, NOT_YOUR_TURN, ALREADY_IN, NO_SHARED_ACTOR, OVER }

/**
 * Movie Chain: the players build one chain of movies, each sharing an
 * actor with the one before, no movie twice. A link scores the years
 * between the two movies, at least one; but when the actor the link before
 * went through is in the next movie too, it is an escape, which keeps the
 * chain going without points. The players take turns, a day each:
 * a turn not taken passes to the next player, worked out when the chain is
 * looked at, so nobody holds the game up; after a week without a move it
 * is over. The players see the movies only; a player who needs a hint is
 * shown one actor of the last movie at a time, in billing order and three
 * at most, each counted among their hints. A player who finds nothing ends it; the most points win, and of
 * the same points the fewest hints.
 * On one's own the house plays the other turns, at once, with a movie of
 * its own, and loses when it finds none. The chain starts from one of the
 * best-known movies the host's movie filter lets through.
 */
class ChainService(
    private val matchService: MatchService,
    private val gameMatchRepository: GameMatchRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val chainLinkRepository: ChainLinkRepository,
    private val principalRepository: PrincipalRepository,
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val personRepository: PersonRepository,
    private val userRepository: UserRepository,
    private val poolService: PoolService
) {

    /** Starts a chain with the opponents, from a well-known movie one of them watched; null when the friend or the club is not the host's. */
    suspend fun start(host: User, opponents: Opponents, now: Instant = Instant.now()): GameMatch? = transaction {
        val stored = userRepository.touch(host, now)
        val (club, players) = matchService.playersOf(stored, opponents) ?: return@transaction null
        val pool = poolService.poolOf(stored, players, GameRules.of(Game.CHAIN).pool, MINIMUM_VOTES, START_POOL)
        val (match, _) = matchService.start(
            GameMatch(game = Game.CHAIN, mode = opponents.mode, club = club, host = stored, createdAt = now, closesAt = null, endedAt = null),
            players,
            now,
            pool
        )
        val movies = pool.movies
        val first = movies.shuffled(Random(now.toEpochMilli())).firstOrNull { actorsOf(it).size >= MINIMUM_CAST } ?: movies.first()
        chainLinkRepository.insert(ChainLink(match, 0, first, player = null, via = null, points = 0, addedAt = now))
        match
    }

    /** The chain for the visitor, or null when they may not look at it. */
    suspend fun chainOf(user: User, matchId: Long, now: Instant = Instant.now()): ChainView? = transaction(readOnly = true) {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CHAIN } ?: return@transaction null
        val links = chainLinkRepository.findLinks(match)
        val players = gamePlayerRepository.findPlayers(match)
        val over = isOver(match, links, now)
        val turn = if (over) null else turnOf(players, links.last(), now)
        val me = players.firstOrNull { it.user.id == user.id }
        val lastPosition = links.last().position
        // The actors shown as hints: the visitor's, for the last movie, on their turn.
        val shown = if (turn?.player?.user?.id == user.id && me?.hintAt == lastPosition) me.hintShown else 0
        val actors = if (over) emptyList() else actorsOf(links.last().movie)
        ChainView(
            match = match,
            links = links,
            players = players,
            turn = turn,
            yourTurn = turn?.player?.user?.id == user.id,
            over = over,
            revealed = people(actors.take(shown)),
            moreHints = shown < minOf(actors.size, HINTS_PER_MOVIE),
            answers = if (over) answersOf(links) else emptyList()
        )
    }

    /**
     * A hint for the player whose turn it is: one more actor of the last
     * movie, in billing order, [HINTS_PER_MOVIE] at most, each counted among
     * their hints.
     */
    suspend fun hint(user: User, matchId: Long, now: Instant = Instant.now()) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CHAIN } ?: return@transaction
        val links = chainLinkRepository.findLinks(match)
        if (isOver(match, links, now)) return@transaction
        val turn = turnOf(gamePlayerRepository.findPlayers(match), links.last(), now)
        if (turn.player.user.id != user.id) return@transaction
        val player = turn.player
        val lastPosition = links.last().position
        val shown = if (player.hintAt == lastPosition) player.hintShown else 0
        if (shown >= minOf(actorsOf(links.last().movie).size, HINTS_PER_MOVIE)) return@transaction
        gamePlayerRepository.update(player.copy(hints = player.hints + 1, hintAt = lastPosition, hintShown = shown + 1, updatedAt = now))
    }

    /**
     * The best-known movies that share an actor with the chain's last movie
     * and are not in it, each with that actor: where the chain could have
     * gone on, shown once it is over.
     */
    private fun answersOf(links: List<ChainLink>): List<ChainAnswer> {
        val actors = actorsOf(links.last().movie)
        if (actors.isEmpty()) return emptyList()
        val used = links.map { it.movie.id }.toSet()
        val credits = principalRepository.findActingLinks(actors.map { refById<Person>(it) }, ANSWER_VOTES)
            .filter { it.movie.entityId() !in used }
        // Each movie through its best-billed actor of the last movie.
        val actorOf = credits.groupBy { it.movie.entityId() }
            .mapValues { (_, links) -> links.minBy { link -> actors.indexOf(link.person.entityId()) }.person.entityId() }
        val ratings = ratingRepository.findByMovies(actorOf.keys.map { refById<Movie>(it) })
            .sortedByDescending { it.voteCount }
            .take(ANSWERS)
        val people = personRepository.findAllById(ratings.map { actorOf.getValue(it.movie.id) }).associateBy { it.id }
        return ratings.map { ChainAnswer(it.movie, people.getValue(actorOf.getValue(it.movie.id))) }
    }

    /** What a chain looks like at [now], as a short text that changes whenever a move is made or a turn passes on. */
    fun versionOf(match: GameMatch, players: List<GamePlayer>, now: Instant): String {
        val links = chainLinkRepository.findLinks(match)
        val turn = if (isOver(match, links, now)) null else turnOf(players, links.last(), now)
        return "${links.size}|${turn?.player?.user?.id}|${players.sumOf { it.hints }}"
    }

    /**
     * Whose turn it is in a chain at [now], or null once it is over: for the
     * lists of one's games, inside their transaction.
     */
    fun turnIn(match: GameMatch, players: List<GamePlayer>, now: Instant): Turn? {
        val links = chainLinkRepository.findLinks(match)
        return if (isOver(match, links, now)) null else turnOf(players, links.last(), now)
    }

    /**
     * Whose turn it is after [last]: the player after the one who added it,
     * the first player after the first movie or the house's, moved on by
     * one for every day that went by without a move.
     */
    private fun turnOf(players: List<GamePlayer>, last: ChainLink, now: Instant): Turn {
        val after = last.player?.let { player -> players.indexOfFirst { it.user.id == player.id } + 1 } ?: 0
        val skipped = (Duration.between(last.addedAt, now).toMillis() / TURN.toMillis()).toInt()
        // On one's own nothing passes: the player is the only one, and the house plays at once.
        val index = if (players.size == 1) 0 else (after + skipped) % players.size
        val until = last.addedAt.plus(TURN.multipliedBy(skipped + 1L))
        return Turn(players[index], until, if (players.size == 1) 0 else skipped, Duration.between(now, until).toHours().coerceAtLeast(0))
    }

    /** Over once ended, or after a week without a move. */
    private fun isOver(match: GameMatch, links: List<ChainLink>, now: Instant): Boolean =
        match.endedAt != null || Duration.between(links.last().addedAt, now) > IDLE

    /**
     * Adds a movie to the chain, for the player whose turn it is: one not
     * in it yet that shares an actor with the last. On one's own the house
     * answers at once; when it finds nothing, the chain is over.
     */
    suspend fun move(user: User, matchId: Long, movieId: String, now: Instant = Instant.now()): ChainMove = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CHAIN } ?: return@transaction ChainMove.OVER
        val links = chainLinkRepository.findLinks(match)
        if (isOver(match, links, now)) return@transaction ChainMove.OVER
        val players = gamePlayerRepository.findPlayers(match)
        val turn = turnOf(players, links.last(), now)
        if (turn.player.user.id != user.id) return@transaction ChainMove.NOT_YOUR_TURN
        val movie = movieRepository.findById(movieId) ?: return@transaction ChainMove.NO_SHARED_ACTOR
        if (links.any { it.movie.id == movie.id }) return@transaction ChainMove.ALREADY_IN
        val previous = links.last()
        val via = sharedActor(previous.movie, movie, previousVia = previous.via) ?: return@transaction ChainMove.NO_SHARED_ACTOR
        val points = pointsFor(previous, movie, via)
        chainLinkRepository.insert(ChainLink(match, links.size, movie, turn.player.user, via, points, now))
        gamePlayerRepository.update(turn.player.copy(score = turn.player.score + points, updatedAt = now))
        if (match.mode == GameMode.SOLO) {
            houseMove(match, links.map { it.movie.id }.toSet() + movie.id, ChainLink(match, links.size, movie, turn.player.user, via, points, now), links.size + 1, now)
        }
        ChainMove.ADDED
    }

    /** The player whose turn it is finds nothing: the chain is over, and the points stand. */
    suspend fun giveUp(user: User, matchId: Long, now: Instant = Instant.now()) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CHAIN } ?: return@transaction
        val links = chainLinkRepository.findLinks(match)
        if (isOver(match, links, now)) return@transaction
        if (turnOf(gamePlayerRepository.findPlayers(match), links.last(), now).player.user.id != user.id) return@transaction
        gameMatchRepository.update(match.copy(endedAt = now))
    }

    /**
     * The house's answer, on one's own: a well-known movie that shares an
     * actor with the player's and is not in the chain yet, picked at random,
     * through the player's actor when that actor is in it too, an escape;
     * when there is none, the house is stuck and the chain is over.
     */
    private fun houseMove(match: GameMatch, used: Set<String>, after: ChainLink, position: Int, now: Instant) {
        val actors = actorsOf(after.movie)
        val candidates = principalRepository.findActingLinks(actors.map { refById<Person>(it) }, HOUSE_VOTES)
            .filter { it.movie.entityId() !in used }
            .groupBy { it.movie.entityId() }
        val movieId = candidates.keys.randomOrNull()
        if (movieId == null) {
            gameMatchRepository.update(match.copy(endedAt = now))
            return
        }
        val through = candidates.getValue(movieId).map { it.person.entityId() }
        val viaId = through.firstOrNull { it == after.via?.id } ?: through.first()
        val movie = movieRepository.getById(movieId)
        val via = personRepository.getById(viaId)
        chainLinkRepository.insert(ChainLink(match, position, movie, player = null, via = via, points = pointsFor(after, movie, via), addedAt = now))
    }

    /** The people with these ids, in this order. */
    private fun people(ids: List<String>): List<Person> {
        val people = personRepository.findAllById(ids).associateBy { it.id }
        return ids.mapNotNull(people::get)
    }

    /** The acting credits of a movie, by the people's ids, in billing order. */
    private fun actorsOf(movie: Movie): List<String> =
        principalRepository.findCast(movie).filter { it.category in ACTING_CATEGORIES }.map { it.person.id }.distinct()

    /**
     * An actor of both movies, the one the link goes through: the actor the
     * last link went through ([previousVia]) when the next movie has them too,
     * an escape; else Kevin Bacon when both have him, for his bonus, or
     * Meryl Streep, Bruce Willis or Jack Nicholson for theirs; else the best
     * billed of the next movie that both share.
     */
    private fun sharedActor(previous: Movie, next: Movie, previousVia: Person?): Person? {
        val before = actorsOf(previous).toSet()
        val shared = principalRepository.findCast(next).filter { it.category in ACTING_CATEGORIES && it.person.id in before }.map { it.person }
        return shared.firstOrNull { it.id == previousVia?.id } ?: shared.firstOrNull { it.id == KEVIN_BACON } ?: shared.firstOrNull { it.id == MERYL_STREEP } ?: shared.firstOrNull { it.id == BRUCE_WILLIS } ?: shared.firstOrNull { it.id == JACK_NICHOLSON } ?: shared.firstOrNull()
    }

    /**
     * The points of a link after [previous]: none when it goes through the
     * same actor as [previous]'s link, that actor in the next movie too, an
     * escape that keeps the chain going; else the years between the two
     * movies, at least one, so a leap across the eras scores most, and
     * [BACON_BONUS] more through Kevin Bacon, [STREEP_BONUS] through Meryl
     * Streep, [WILLIS_BONUS] through Bruce Willis, [NICHOLSON_BONUS] through Jack Nicholson.
     */
    private fun pointsFor(previous: ChainLink, next: Movie, via: Person): Int {
        if (previous.via?.id == via.id) return 0
        val years = previous.movie.startYear?.let { from -> next.startYear?.let { abs(it - from) } } ?: 0
        return years.coerceAtLeast(1) + when (via.id) {
            KEVIN_BACON -> BACON_BONUS
            MERYL_STREEP -> STREEP_BONUS
            BRUCE_WILLIS -> WILLIS_BONUS
            JACK_NICHOLSON -> NICHOLSON_BONUS
            else -> 0
        }
    }

    companion object {
        /** A day a turn. */
        val TURN: Duration = Duration.ofHours(24)
        /** A chain without a move for a week is over. */
        private val IDLE: Duration = Duration.ofDays(7)
        private const val START_POOL = 300
        private const val MINIMUM_VOTES = 25_000
        private const val MINIMUM_CAST = 3
        /** The house plays well-known movies too. */
        private const val HOUSE_VOTES = 50_000
        /** The actors of a movie a player can be shown as hints, in billing order. */
        const val HINTS_PER_MOVIE = 3
        /** The points more a link through Kevin Bacon scores: six, for the six degrees. */
        const val BACON_BONUS = 6
        /** The points more a link through Meryl Streep scores: three, for her three Oscars. */
        const val STREEP_BONUS = 3
        /** The points more a link through Bruce Willis scores: five, one for each Die Hard. */
        const val WILLIS_BONUS = 5
        /** The points more a link through Jack Nicholson scores: three, for his three Oscars. */
        const val NICHOLSON_BONUS = 3
        /** The ways the chain could have gone on, shown once it is over. */
        private const val ANSWERS = 5
        private const val ANSWER_VOTES = 1_000
    }
}
