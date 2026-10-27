package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.Level
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.transaction
import kotlin.math.ceil

/**
 * Where a user stands: their level, their score, the movies watched and
 * checked in that make it, and how far the next level is, as a share of the
 * way from this level to the next, for the ring around the avatar.
 */
data class LevelProgress(val level: Level, val score: Double, val watched: Long, val checkedIn: Long) {
    val next: Level? get() = level.next
    /** The movies still to watch for the next level, a check-in counting one and a half; 0 at the top. */
    val toNext: Int get() = next?.let { ceil(it.threshold - score).toInt().coerceAtLeast(0) } ?: 0
    /** The way from this level to the next, from 0 to 1; full at the top. */
    val fraction: Double get() = next?.let { ((score - level.threshold) / (it.threshold - level.threshold)).coerceIn(0.0, 1.0) } ?: 1.0
    /** The next level's name and what is still to watch for it, in a line. */
    val nextText: String get() = next?.let { "$toNext more to ${it.label}" } ?: "The top of the bill"
    /** The same, for the scripts. */
    fun standing(newBadges: List<String> = emptyList()): LevelStanding = LevelStanding(level.label, score, next?.label, toNext, fraction, newBadges)
}

/** Where a user stands, as the page's scripts read it after a check-in, with the badges it brought. */
@Serializable
data class LevelStanding(val level: String, val score: Double, val next: String?, val toNext: Int, val fraction: Double, val newBadges: List<String> = emptyList())

/** Why a user cannot start a game: the level it needs, and how many movies they still have to watch for it. */
data class GameLock(val game: Game, val level: Level, val toGo: Int) {
    val text: String get() = "Unlocks at ${level.label} · ${if (toGo == 1) "1 movie" else "$toGo movies"} to go"
}

/** A rung of the ladder on the levels page: the level, the games it unlocks, and whether the user is there yet. */
data class LevelRung(val level: Level, val unlocks: List<GameRule>, val reached: Boolean, val current: Boolean)

/** The levels page: the user's progress, the ladder, every game's rule, and the posters of their latest watched movies, for the wall. */
data class LevelsView(val progress: LevelProgress, val rungs: List<LevelRung>, val rules: List<GameRule>, val posters: List<Movie>) {
    /** The wall's rows of posters, a third of them each. */
    val posterRows: List<List<Movie>> get() = posters.chunked(((posters.size + 2) / 3).coerceAtLeast(1))
}

/**
 * The levels: a user's score is the movies they watched, one each, and a
 * half more for every movie they checked in, so watching on the site counts
 * more than an imported history. The level the score reaches is kept on the
 * user, raised whenever it is looked at and never lowered, and decides which
 * games they can start ([GameRules]).
 */
class LevelService(
    private val userRepository: UserRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val checkInRepository: CheckInRepository
) {

    /** Where the user stands now, their stored level raised when the score reached a new one. */
    suspend fun progressOf(user: User): LevelProgress = transaction { progressIn(user) }

    /** The same, inside a transaction of the caller's. */
    fun progressIn(user: User): LevelProgress {
        val watched = movieSeenRepository.countFor(user)
        val checkedIn = checkInRepository.countMovies(user)
        val score = watched + CHECK_IN_EXTRA * checkedIn
        val reached = Level.of(score)
        // A level once reached is kept: the stored one is raised, never lowered.
        val level = if (reached.reaches(user.level)) reached else user.level
        if (level != user.level) userRepository.update(userRepository.getById(user.id).copy(level = level))
        return LevelProgress(level, score, watched, checkedIn)
    }

    /** The games a user at [progress] cannot start yet, by the game's name, each with why. */
    fun locksOf(progress: LevelProgress): Map<String, GameLock> =
        GameRules.all.filter { !progress.level.reaches(it.level) }
            .associate { it.game.name to GameLock(it.game, it.level, ceil(it.level.threshold - progress.score).toInt().coerceAtLeast(1)) }

    /** The games the user cannot start yet, by the game's name, each with why. */
    suspend fun locksOf(user: User): Map<String, GameLock> = locksOf(progressOf(user))

    /** Whether a user at [progress] may start a game: their level reaches the game's. */
    fun canStart(progress: LevelProgress, game: Game): Boolean = progress.level.reaches(GameRules.of(game).level)

    /** Whether the user may start a game: their level reaches the game's. */
    suspend fun canStart(user: User, game: Game): Boolean = canStart(progressOf(user), game)

    /** The levels page for the user. */
    suspend fun levelsOf(user: User): LevelsView = levelsOf(user, progressOf(user))

    /** The levels page for the user, at [progress]. */
    suspend fun levelsOf(user: User, progress: LevelProgress): LevelsView = transaction {
        LevelsView(
            progress,
            Level.entries.map { LevelRung(it, GameRules.unlockedAt(it), progress.level.reaches(it), it == progress.level) },
            GameRules.all,
            movieSeenRepository.findMostRecent(user, WALL_POSTERS).map { it.movie }
        )
    }

    companion object {
        /** What a check-in adds to a watched movie's one point. */
        const val CHECK_IN_EXTRA = 0.5
        private const val WALL_POSTERS = 36
    }
}
