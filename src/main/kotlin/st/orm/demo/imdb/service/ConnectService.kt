package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.ChainLink
import st.orm.demo.imdb.model.ConnectPath
import st.orm.demo.imdb.model.ConnectPuzzle
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.GamePlayerPk
import st.orm.demo.imdb.model.KEVIN_BACON
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.ChainLinkRepository
import st.orm.demo.imdb.repository.ConnectPuzzleRepository
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
import java.time.Instant
import kotlin.random.Random

/** A step of the way par takes: the movie, and the actor it shares with the one before. */
data class ParStep(val movie: Movie, val actor: Person?)

/**
 * A Connect puzzle for a player: where it starts and must end, the chain so
 * far, par, the team's hints and undos, the actors shown as hints for the
 * chain's end, and, once over, how it went and the way par takes.
 */
data class ConnectView(
    val match: GameMatch,
    val puzzle: ConnectPuzzle,
    val links: List<ChainLink>,
    val players: List<GamePlayer>,
    val revealed: List<Person>,
    val moreHints: Boolean,
    val connected: Boolean,
    val over: Boolean,
    val parWay: List<ParStep>
) {
    val last: ChainLink get() = links.last()
    /** The links the team made, from the start to the target, once connected. */
    val linkCount: Int get() = links.size - 1
    /** The links through Kevin Bacon: each counts one less. */
    val baconLinks: Int get() = links.count { it.throughBacon }
    /** How the team did against par, a link off for each through Kevin Bacon: 0 on par, more over it. */
    val overPar: Int get() = linkCount - baconLinks - puzzle.par
    /** Who added how many links. */
    fun linksBy(player: GamePlayer): Int = links.count { it.player?.id == player.user.id }
}

/** What came of a move. */
enum class ConnectMove { ADDED, CONNECTED, ALREADY_IN, NO_SHARED_ACTOR, OVER }

/**
 * Connect: two movies, the start and the target, to join by a chain of
 * movies, each sharing an actor with the one before; once the chain's end
 * shares one with the target, it is connected. Par is the fewest links it
 * takes, found when the puzzle is made by a search from both movies at once.
 *
 * It is played together: on one's own, with a friend or with a club, every
 * player adds a link whenever they like, no turns, so nobody holds it up.
 * A player may take the chain's last link back, to try another way, or be
 * shown an actor of its end, three at most; the team's undos and hints
 * count beside its links. Giving up shows the way par takes.
 */
class ConnectService(
    private val matchService: MatchService,
    private val gameMatchRepository: GameMatchRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val connectPuzzleRepository: ConnectPuzzleRepository,
    private val chainLinkRepository: ChainLinkRepository,
    private val principalRepository: PrincipalRepository,
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val personRepository: PersonRepository,
    private val userRepository: UserRepository,
    private val poolService: PoolService,
    /** The fewest links a puzzle should take, and the most a search looks for. */
    private val parRange: IntRange = PAR_RANGE
) {

    /**
     * Makes a puzzle with the opponents: two of the best-known movies of the
     * host's taste, a few links apart; null when the friend or the club is not
     * the host's, or no two movies were found far enough apart.
     */
    suspend fun start(host: User, opponents: Opponents, now: Instant = Instant.now()): GameMatch? {
        val pool = transaction(readOnly = true) {
            val players = matchService.playersOf(host, opponents)?.second ?: return@transaction null
            poolService.poolOf(host, players, GameRules.of(Game.CONNECT).pool, MINIMUM_VOTES, POOL)
        } ?: return null
        val random = Random(now.toEpochMilli())
        repeat(TRIES) {
            val pair = pool.movies.shuffled(random).take(2)
            if (pair.size == 2) startWith(host, opponents, pair[0], pair[1], now, pool)?.let { return it }
        }
        return null
    }

    /**
     * Makes a puzzle from [from] to [to], when they are as far apart as par
     * may be; null when they are not, or when the friend or the club is not
     * the host's.
     */
    suspend fun startWith(host: User, opponents: Opponents, from: Movie, to: Movie, now: Instant = Instant.now(), pool: MoviePool? = null): GameMatch? = transaction {
        val path = shortestPath(from.id, to.id)?.takeIf { it.movies.size - 1 in parRange } ?: return@transaction null
        val stored = userRepository.touch(host, now)
        val (club, players) = matchService.playersOf(stored, opponents) ?: return@transaction null
        val (match, _) = matchService.start(
            GameMatch(game = Game.CONNECT, mode = opponents.mode, club = club, host = stored, createdAt = now, closesAt = null, endedAt = null),
            players,
            now,
            pool
        )
        connectPuzzleRepository.insert(ConnectPuzzle(match, from, to, path.movies.size - 1, path))
        chainLinkRepository.insert(ChainLink(match, 0, from, player = null, via = null, points = 0, addedAt = now))
        match
    }

    /** The puzzle for the visitor, or null when they may not look at it. */
    suspend fun puzzleOf(user: User, matchId: Long): ConnectView? = transaction(readOnly = true) {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CONNECT } ?: return@transaction null
        val puzzle = connectPuzzleRepository.getById(match)
        val links = chainLinkRepository.findLinks(match)
        val connected = links.last().movie.id == puzzle.target.id
        val over = connected || match.endedAt != null
        val actors = if (over) emptyList() else actorsOf(links.last().movie)
        val shown = if (puzzle.hintAt == links.last().position) puzzle.hintShown else 0
        ConnectView(
            match = match,
            puzzle = puzzle,
            links = links,
            players = gamePlayerRepository.findPlayers(match),
            revealed = people(actors.take(shown)),
            moreHints = shown < minOf(actors.size, HINTS_PER_MOVIE),
            connected = connected,
            over = over,
            parWay = if (over) parWayOf(puzzle.path) else emptyList()
        )
    }

    /** How a puzzle stands, for the lists of one's games, inside their transaction: whether it is over, and in a line. */
    fun standingOf(match: GameMatch): Pair<Boolean, String> {
        val puzzle = connectPuzzleRepository.getById(match)
        val links = chainLinkRepository.findLinks(match)
        return when {
            links.last().movie.id == puzzle.target.id -> true to "Connected in ${links.size - 1}, par ${puzzle.par}" +
                (if (links.any { it.throughBacon }) ", with a Bacon bonus" else "")
            match.endedAt != null -> true to "Not connected: par was ${puzzle.par}"
            else -> false to "${puzzle.start.primaryTitle} to ${puzzle.target.primaryTitle}: add a link"
        }
    }

    /** What a puzzle looks like, as a short text that changes with every move: for the pages that update by themselves. */
    fun versionOf(match: GameMatch): String {
        val puzzle = connectPuzzleRepository.getById(match)
        return "${chainLinkRepository.findLinks(match).size}|${puzzle.hints}|${puzzle.undos}"
    }

    /**
     * Adds a movie to the chain, for any player: one not in it yet that
     * shares an actor with the chain's end. When it is the target, or shares
     * an actor with it, the chain is connected, the target its last link.
     */
    suspend fun move(user: User, matchId: Long, movieId: String, now: Instant = Instant.now()): ConnectMove = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CONNECT } ?: return@transaction ConnectMove.OVER
        val puzzle = connectPuzzleRepository.getById(match)
        val links = chainLinkRepository.findLinks(match)
        if (match.endedAt != null || links.last().movie.id == puzzle.target.id) return@transaction ConnectMove.OVER
        val player = playerOf(user, match, now) ?: return@transaction ConnectMove.OVER
        val movie = movieRepository.findById(movieId) ?: return@transaction ConnectMove.NO_SHARED_ACTOR
        if (links.any { it.movie.id == movie.id }) return@transaction ConnectMove.ALREADY_IN
        val via = sharedActor(links.last().movie, movie) ?: return@transaction ConnectMove.NO_SHARED_ACTOR
        chainLinkRepository.insert(ChainLink(match, links.size, movie, player.user, via, points = 1, addedAt = now))
        gamePlayerRepository.update(player.copy(score = player.score + 1, updatedAt = now))
        if (movie.id == puzzle.target.id) return@transaction ConnectMove.CONNECTED
        // Sharing an actor with the target: the target closes the chain.
        val toTarget = sharedActor(movie, puzzle.target) ?: return@transaction ConnectMove.ADDED
        chainLinkRepository.insert(ChainLink(match, links.size + 1, puzzle.target, player.user, toTarget, points = 1, addedAt = now))
        ConnectMove.CONNECTED
    }

    /** Takes the chain's last link back, for any player, to try another way; counted among the team's undos. */
    suspend fun undo(user: User, matchId: Long, now: Instant = Instant.now()) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CONNECT && it.endedAt == null } ?: return@transaction
        val puzzle = connectPuzzleRepository.getById(match)
        val links = chainLinkRepository.findLinks(match)
        val last = links.last().takeIf { it.position > 0 && it.movie.id != puzzle.target.id } ?: return@transaction
        playerOf(user, match, now) ?: return@transaction
        chainLinkRepository.remove(last)
        connectPuzzleRepository.update(puzzle.copy(undos = puzzle.undos + 1))
    }

    /** Shows one more actor of the chain's end, three at most, for any player; counted among the team's hints. */
    suspend fun hint(user: User, matchId: Long, now: Instant = Instant.now()) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CONNECT && it.endedAt == null } ?: return@transaction
        val puzzle = connectPuzzleRepository.getById(match)
        val last = chainLinkRepository.findLinks(match).last()
        if (last.movie.id == puzzle.target.id) return@transaction
        playerOf(user, match, now) ?: return@transaction
        val shown = if (puzzle.hintAt == last.position) puzzle.hintShown else 0
        if (shown >= minOf(actorsOf(last.movie).size, HINTS_PER_MOVIE)) return@transaction
        connectPuzzleRepository.update(puzzle.copy(hints = puzzle.hints + 1, hintAt = last.position, hintShown = shown + 1))
    }

    /** The team gives up: the puzzle is over, and the way par takes is shown. */
    suspend fun giveUp(user: User, matchId: Long, now: Instant = Instant.now()) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.CONNECT && it.endedAt == null } ?: return@transaction
        playerOf(user, match, now) ?: return@transaction
        gameMatchRepository.update(match.copy(endedAt = now))
    }

    /**
     * The visitor's player, inside a transaction that found the match visible
     * to them: a club's member who joined the club since the puzzle was made
     * joins it with their first move. Null for anyone else who is not a player.
     */
    private fun playerOf(user: User, match: GameMatch, now: Instant): GamePlayer? {
        gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = user.id))?.let { return it }
        if (match.mode != GameMode.CLUB) return null
        val player = GamePlayer(match, userRepository.touch(user, now), gamePlayerRepository.findPlayers(match).size, now)
        gamePlayerRepository.insert(player)
        return player
    }

    /* ── The search ──────────────────────────────────────────────── */

    /** One end of the search: the movies it reached, each through which movie and actor, and the ones reached last. */
    private class Side(start: String) {
        val reached = hashMapOf<String, Pair<String, String>?>(start to null)
        val people = hashSetOf<String>()
        var frontier = listOf(start)
    }

    /**
     * The fewest links between two movies, through the acting credits of the
     * movies at least [SEARCH_VOTES] voted on: a breadth-first search from
     * both at once, always from the end that reached fewer movies, each step
     * two queries, from the movies to their casts and from the casts to their
     * movies. Null when they are further apart than par may be.
     */
    private fun shortestPath(fromId: String, toId: String): ConnectPath? {
        if (fromId == toId) return null
        val start = Side(fromId)
        val end = Side(toId)
        repeat(parRange.last) {
            val side = if (start.frontier.size <= end.frontier.size) start else end
            val other = if (side === start) end else start
            // Of the movies where the searches meet, the one the other end reached soonest makes the shortest way.
            expand(side, other).minByOrNull { trail(other, it).size }?.let { meeting -> return pathThrough(start, end, meeting) }
            if (side.frontier.isEmpty()) return null
        }
        return null
    }

    /** The way from the start through [meeting] to the target: the movies in turn, and the actor each shares with the one before. */
    private fun pathThrough(start: Side, end: Side, meeting: String): ConnectPath {
        val forward = trail(start, meeting).reversed()
        val backward = trail(end, meeting)
        val movies = forward.map { it.first } + backward.drop(1).map { it.first }
        val actors = forward.drop(1).map { it.second!! } + backward.dropLast(1).map { it.second!! }
        return ConnectPath(movies, actors)
    }

    /** One step out from [side]: the casts of its last movies, then their other movies; the movies [other] reached too. */
    private fun expand(side: Side, other: Side): List<String> {
        val casts = side.frontier.chunked(CHUNK).flatMap { movies -> principalRepository.findCastLinks(movies.map { refById<Movie>(it) }) }
        val viaMovie = linkedMapOf<String, String>()
        casts.forEach { credit ->
            val person = credit.person.entityId()
            if (person !in side.people) viaMovie.putIfAbsent(person, credit.movie.entityId())
        }
        side.people += viaMovie.keys
        val next = mutableListOf<String>()
        viaMovie.keys.chunked(CHUNK).forEach { people ->
            principalRepository.findActingLinks(people.map { refById<Person>(it) }, SEARCH_VOTES).forEach { credit ->
                val movie = credit.movie.entityId()
                if (movie !in side.reached) {
                    val person = credit.person.entityId()
                    side.reached[movie] = viaMovie.getValue(person) to person
                    next += movie
                }
            }
        }
        side.frontier = next
        return next.filter { it in other.reached }
    }

    /** The way from [movie] back to the side's start: each movie with the actor it shares with the next one toward the start. */
    private fun trail(side: Side, movie: String): List<Pair<String, String?>> {
        val trail = mutableListOf<Pair<String, String?>>()
        var current: String? = movie
        while (current != null) {
            val step = side.reached[current]
            trail += current to step?.second
            current = step?.first
        }
        return trail
    }

    /** The way par takes, as the page shows it: the movies, each with the actor it shares with the one before. */
    private fun parWayOf(path: ConnectPath): List<ParStep> {
        val movies = movieRepository.findAllById(path.movies).associateBy { it.id }
        val actors = personRepository.findAllById(path.actors).associateBy { it.id }
        return path.movies.mapIndexedNotNull { i, id -> movies[id]?.let { ParStep(it, if (i == 0) null else actors[path.actors[i - 1]]) } }
    }

    /** An actor of both movies, the one the link goes through: Kevin Bacon when both have him, for his bonus, else the best billed of the second. */
    private fun sharedActor(previous: Movie, next: Movie): Person? {
        val before = actorsOf(previous).toSet()
        val shared = principalRepository.findCast(next).filter { it.category in ACTING_CATEGORIES && it.person.id in before }.map { it.person }
        return shared.firstOrNull { it.id == KEVIN_BACON } ?: shared.firstOrNull()
    }

    /** The acting credits of a movie, by the people's ids, in billing order. */
    private fun actorsOf(movie: Movie): List<String> =
        principalRepository.findCast(movie).filter { it.category in ACTING_CATEGORIES }.map { it.person.id }.distinct()

    private fun people(ids: List<String>): List<Person> {
        val people = personRepository.findAllById(ids).associateBy { it.id }
        return ids.mapNotNull(people::get)
    }

    companion object {
        /** A puzzle takes three or four links at its best: two is too easy, five too far to find. */
        val PAR_RANGE = 3..4
        const val HINTS_PER_MOVIE = 3
        private const val MINIMUM_VOTES = 25_000
        private const val POOL = 300
        private const val TRIES = 8
        /** The movies a way goes through: known enough to think of. */
        private const val SEARCH_VOTES = 25_000
        private const val CHUNK = 1000
    }
}
