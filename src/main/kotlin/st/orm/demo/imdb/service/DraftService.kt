package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.DraftPick
import st.orm.demo.imdb.model.DraftTheme
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.DraftPickRepository
import st.orm.demo.imdb.repository.DraftThemeRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.ref
import st.orm.template.transaction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Who drafts in a turn: a player, or, on one's own, the house ([player] null).
 */
data class Drafter(val player: GamePlayer?) {
    val name: String get() = player?.user?.name ?: if (player == null) "The house" else "You"
    fun isUser(user: User): Boolean = player?.user?.id == user.id
}

/** A drafter's line-up: their picks, and once the draft is over the sum of the picks' IMDb ratings. */
data class LineUp(val drafter: Drafter, val picks: List<DraftPick>, val total: BigDecimal?)

/**
 * A Movie Draft for a player: its theme, the picks so far, whose turn it is
 * and until when, the movies of the theme still to pick, the best known
 * first, and every drafter's line-up, with their totals once it is over.
 */
data class DraftView(
    val match: GameMatch,
    val theme: DraftTheme,
    val picks: List<DraftPick>,
    val lineUps: List<LineUp>,
    val turn: Drafter?,
    val hoursLeft: Long,
    val yourTurn: Boolean,
    val board: List<Movie>,
    val rounds: Int
) {
    val over: Boolean get() = turn == null
    /** The line-ups best first, once it is over: the winner first. */
    val standings: List<LineUp> get() = lineUps.sortedByDescending { it.total ?: BigDecimal.ZERO }
}

/** What came of a pick. */
enum class DraftMove { PICKED, NOT_YOUR_TURN, TAKEN, OFF_THEME, OVER }

/**
 * Movie Draft: the players draft a line-up of [rounds] movies each for a
 * theme, a genre and a decade, in a snake order, the first to pick last in
 * the next round. The IMDb ratings stay out of sight while drafting; once
 * every pick is in, the line-up with the highest total wins. A turn is a
 * day: one let go by is picked for its player, at random from the board,
 * worked out whenever the draft is looked at, so nobody holds it up. On
 * one's own the house drafts too, at once, a well-known movie of the theme,
 * and the player takes their time.
 * The theme is one with enough of the best-known movies the host's filter
 * lets through.
 */
class DraftService(
    private val matchService: MatchService,
    private val gamePlayerRepository: GamePlayerRepository,
    private val draftThemeRepository: DraftThemeRepository,
    private val draftPickRepository: DraftPickRepository,
    private val genreRepository: GenreRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val userRepository: UserRepository,
    /** The picks each drafter makes. */
    private val rounds: Int = ROUNDS,
    /** A theme has at least this many movies for every pick that is made. */
    private val margin: Int = BOARD_MARGIN
) {

    /** Starts a draft with the opponents, on a theme of the host's taste; null when the friend or the club is not the host's. */
    suspend fun start(host: User, opponents: Opponents, now: Instant = Instant.now()): GameMatch? = transaction {
        val stored = userRepository.touch(host, now)
        val (club, players) = matchService.playersOf(stored, opponents) ?: return@transaction null
        val needed = (players.size + if (opponents.mode == GameMode.SOLO) 1 else 0) * rounds * margin
        val random = Random(now.toEpochMilli())
        val themes = genreRepository.findAll().flatMap { genre -> DECADES.map { genre to it } }.shuffled(random)
        // A theme of the host's taste with enough movies for every pick, else one of everybody's; none, and there is no draft.
        val (genre, decade) = themes.firstOrNull { (genre, decade) ->
            ratingRepository.findBestKnownIn(genre, decade, decade + 10, stored.movieFilter, MINIMUM_VOTES, needed).size >= needed
        } ?: themes.firstOrNull { (genre, decade) -> ratingRepository.findBestKnownIn(genre, decade, decade + 10, null, MINIMUM_VOTES, needed).size >= needed }
            ?: return@transaction null
        val (match, _) = matchService.start(
            GameMatch(game = Game.DRAFT, mode = opponents.mode, club = club, host = stored, createdAt = now, closesAt = null, endedAt = null),
            players,
            now
        )
        draftThemeRepository.insert(DraftTheme(match, genre, decade))
        match
    }

    /** The draft for the visitor, the turns let go by picked first; null when they may not look at it. */
    suspend fun draftOf(user: User, matchId: Long, now: Instant = Instant.now()): DraftView? = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.DRAFT } ?: return@transaction null
        val theme = draftThemeRepository.getById(match)
        val drafters = draftersOf(match)
        // A draft closed before its end is over as it stands.
        val picks = if (match.endedAt != null) draftPickRepository.findPicks(match) else catchUp(match, theme, drafters, now)
        val turn = if (match.endedAt != null) null else turnOf(drafters, picks.size)
        val over = turn == null
        val ratings = if (over) ratingRepository.findByMovies(picks.map { it.movie.ref() }).associate { it.movie.id to it.averageRating } else emptyMap()
        DraftView(
            match = match,
            theme = theme,
            picks = picks,
            lineUps = drafters.map { drafter ->
                val own = picks.filter { pick -> pick.player?.id == drafter.player?.user?.id }
                LineUp(drafter, own, if (over) own.sumOf { ratings[it.movie.id] ?: BigDecimal.ZERO } else null)
            },
            turn = turn,
            hoursLeft = if (over) 0 else Duration.between(now, deadline(match, picks)).toHours().coerceAtLeast(0),
            yourTurn = turn?.isUser(user) == true,
            board = if (over) emptyList() else boardOf(match, theme, picks).take(BOARD_SHOWN),
            rounds = rounds
        )
    }

    /** What a draft looks like at [now], as a short text that changes whenever a pick is made or a turn passes on. */
    fun versionOf(match: GameMatch, now: Instant): String =
        "${draftPickRepository.findPicks(match).size}|${turnIn(match, now)?.player?.user?.id}"

    /** Whose turn it is in a draft, for the lists of one's games, inside their transaction; null once it is over. */
    fun turnIn(match: GameMatch, now: Instant): Drafter? {
        if (match.endedAt != null) return null
        val drafters = draftersOf(match)
        val picks = draftPickRepository.findPicks(match)
        if (match.mode == GameMode.SOLO) return turnOf(drafters, picks.size)
        // The turns let go by count as picked, without writing them in a read.
        val missed = Duration.between(deadline(match, picks).minus(TURN), now).toMillis() / TURN.toMillis()
        return turnOf(drafters, picks.size + missed.toInt())
    }

    /**
     * Picks a movie for the player whose turn it is: a movie of the theme,
     * not picked yet. On one's own the house picks at once after them.
     */
    suspend fun pick(user: User, matchId: Long, movieId: String, now: Instant = Instant.now()): DraftMove = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.DRAFT && it.endedAt == null } ?: return@transaction DraftMove.OVER
        val theme = draftThemeRepository.getById(match)
        val drafters = draftersOf(match)
        var picks = catchUp(match, theme, drafters, now)
        val turn = turnOf(drafters, picks.size) ?: return@transaction DraftMove.OVER
        if (!turn.isUser(user)) return@transaction DraftMove.NOT_YOUR_TURN
        val movie = movieRepository.findById(movieId) ?: return@transaction DraftMove.OFF_THEME
        if (picks.any { it.movie.id == movie.id }) return@transaction DraftMove.TAKEN
        if (!fits(theme, movie)) return@transaction DraftMove.OFF_THEME
        draftPickRepository.insert(DraftPick(match, picks.size, movie, turn.player!!.user, auto = false, pickedAt = now))
        picks = draftPickRepository.findPicks(match)
        // The house's turns that follow, at once.
        while (turnOf(drafters, picks.size)?.player == null && turnOf(drafters, picks.size) != null) {
            val choice = boardOf(match, theme, picks).take(HOUSE_CHOICE).randomOrNull() ?: break
            draftPickRepository.insert(DraftPick(match, picks.size, choice, player = null, auto = false, pickedAt = now))
            picks = draftPickRepository.findPicks(match)
        }
        DraftMove.PICKED
    }

    /** The drafters in their order: the players by their turns, and on one's own the house after the player. */
    private fun draftersOf(match: GameMatch): List<Drafter> {
        val players = gamePlayerRepository.findPlayers(match).map(::Drafter)
        return if (match.mode == GameMode.SOLO) players + Drafter(null) else players
    }

    /** Who picks the [position]th pick, in the snake order; null once every round is drafted. */
    private fun turnOf(drafters: List<Drafter>, position: Int): Drafter? {
        val n = drafters.size
        if (position >= n * rounds) return null
        val round = position / n
        val index = position % n
        return drafters[if (round % 2 == 0) index else n - 1 - index]
    }

    /** When the turn at hand runs out: a day after the last pick, or after the start. */
    private fun deadline(match: GameMatch, picks: List<DraftPick>): Instant =
        (picks.lastOrNull()?.pickedAt ?: match.createdAt).plus(TURN)

    /**
     * Picks for the players who let their turn go by, a day each, at random
     * from the board, each as of when their turn ran out; the house picks
     * after them as it does after a pick. Returns the picks, those included.
     */
    private fun catchUp(match: GameMatch, theme: DraftTheme, drafters: List<Drafter>, now: Instant): List<DraftPick> {
        var picks = draftPickRepository.findPicks(match)
        while (true) {
            val turn = turnOf(drafters, picks.size) ?: break
            // On one's own nobody waits: the player's turn stays theirs.
            if (turn.player != null && match.mode == GameMode.SOLO) break
            val due = deadline(match, picks)
            val at = if (turn.player == null) picks.lastOrNull()?.pickedAt ?: match.createdAt else due
            if (turn.player != null && now.isBefore(due)) break
            val board = boardOf(match, theme, picks)
            val choice = (if (turn.player == null) board.take(HOUSE_CHOICE) else board).randomOrNull() ?: break
            draftPickRepository.insert(DraftPick(match, picks.size, choice, turn.player?.user, auto = turn.player != null, pickedAt = at))
            picks = draftPickRepository.findPicks(match)
        }
        return picks
    }

    /** The movies of the theme still to pick, the best known first, of the host's taste. */
    private fun boardOf(match: GameMatch, theme: DraftTheme, picks: List<DraftPick>): List<Movie> {
        val taken = picks.map { it.movie.id }.toSet()
        return ratingRepository.findBestKnownIn(theme.genre, theme.decade, theme.decade + 10, match.host.movieFilter, MINIMUM_VOTES, BOARD_POOL)
            .map { it.movie }
            .filter { it.id !in taken }
    }

    /** Whether a movie fits the theme: of its genre, from its decade, and rated, as the totals add the ratings. */
    private fun fits(theme: DraftTheme, movie: Movie): Boolean {
        val year = movie.startYear ?: return false
        if (year < theme.decade || year >= theme.decade + 10) return false
        if (movieGenreRepository.findGenres(movie).none { it.id == theme.genre.id }) return false
        return ratingRepository.findById(movie) != null
    }

    companion object {
        const val ROUNDS = 3
        /** A day a turn. */
        val TURN: Duration = Duration.ofHours(24)
        private val DECADES = listOf(1970, 1980, 1990, 2000, 2010)
        private const val MINIMUM_VOTES = 25_000
        /** A theme has at least this many movies for every pick to be made: three times the picks. */
        private const val BOARD_MARGIN = 3
        private const val BOARD_POOL = 200
        /** The posters of the board the page shows. */
        private const val BOARD_SHOWN = 24
        /** The house picks among this many of the best known still on the board. */
        private const val HOUSE_CHOICE = 8
    }
}
