package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Badge
import st.orm.demo.imdb.model.ClubInvite_
import st.orm.demo.imdb.model.FriendInvite_
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.Level
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Tag
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubInviteRepository
import st.orm.demo.imdb.repository.FriendInviteRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserBadgeRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.eq
import st.orm.template.transaction
import kotlin.math.ceil

/**
 * What a level asks beyond its points: badges to have earned, and people
 * to have invited who came. Nobody gets up the bill by watching alone.
 */
data class LevelRite(val badges: List<Badge>, val invites: Int)

/** One thing a level asks, and whether the user has it: a badge, or the people invited so far. */
data class RiteStatus(val badge: Badge?, val invited: Int, val needed: Int, val done: Boolean) {
    val text: String get() = badge?.title ?: "$invited of $needed invited"
}

/** The rites of every level: badges from the games, and the people invited. */
object LevelRites {
    private val rites = mapOf(
        Level.BIT_PART to LevelRite(listOf(Badge.DECK_DONE, Badge.HOT_STREAK), 0),
        Level.SUPPORTING to LevelRite(listOf(Badge.CHAIN_GANG, Badge.FIRST_DAILY), 1),
        Level.LEAD to LevelRite(listOf(Badge.WINNER, Badge.PERFECT_GRID), 5),
        // From Star on, each level has a chain link of its own: Bacon, Willis, Nicholson, and Streep at the top.
        Level.STAR to LevelRite(listOf(Badge.CAST_CALL, Badge.ODD_EYE, Badge.BACON_NUMBER, Badge.STAR_SPONSOR), 10),
        Level.SUPER_STAR to LevelRite(listOf(Badge.DAILY_WEEK, Badge.WILLIS_NUMBER), 15),
        Level.LEGEND to LevelRite(listOf(Badge.NIGHT_HOST, Badge.CHAMPION, Badge.NICHOLSON_NUMBER), 20),
        Level.GOAT to LevelRite(listOf(Badge.STREEP_NUMBER), 25)
    )

    fun of(level: Level): LevelRite = rites[level] ?: LevelRite(emptyList(), 0)

    /** How the user stands with a level's rites, given the badges they earned and the people they invited. */
    fun statusOf(level: Level, earned: Set<Badge>, invited: Int): List<RiteStatus> {
        val rite = of(level)
        return rite.badges.map { RiteStatus(it, invited, 0, it in earned) } +
            listOfNotNull(rite.invites.takeIf { it > 0 }?.let { RiteStatus(null, invited, it, invited >= it) })
    }

    /** Whether the user meets a level's rites. */
    fun met(level: Level, earned: Set<Badge>, invited: Int): Boolean = statusOf(level, earned, invited).all { it.done }
}

/**
 * Where a user stands: their level, their score, the movies watched and
 * checked in that make it, how far the next level is, as a share of the
 * way from this level to the next, for the ring around the avatar, and the
 * level the points alone would give ([byPoints]) with what still holds the
 * user back from it ([rites]), when something does.
 */
data class LevelProgress(
    val level: Level,
    val score: Double,
    val watched: Long,
    val checkedIn: Long,
    val byPoints: Level = level,
    val rites: List<RiteStatus> = emptyList()
) {
    /** Whether the points reach a level the rites do not yet. */
    val held: Boolean get() = byPoints.reaches(level) && byPoints != level
    val next: Level? get() = level.next
    /** The movies still to watch for the next level, a check-in counting one and a half; 0 at the top. */
    val toNext: Int get() = next?.let { ceil(it.threshold - score).toInt().coerceAtLeast(0) } ?: 0
    /** The way from this level to the next, from 0 to 1; full at the top. */
    val fraction: Double get() = next?.let { ((score - level.threshold) / (it.threshold - level.threshold)).coerceIn(0.0, 1.0) } ?: 1.0
    /** The next level's name and what is still to watch for it, in a line; or the rites still to do, when the points are there. */
    val nextText: String get() = when {
        held -> rites.count { !it.done }.let { "${it} ${if (it == 1) "rite" else "rites"} to go for ${next?.label}" }
        else -> next?.let { "$toNext more to ${it.label}" } ?: "The greatest of all time"
    }
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

/** A rung of the ladder on the levels page: the level, the games it unlocks, its rites as the user stands with them, and whether the user is there yet. */
data class LevelRung(val level: Level, val unlocks: List<GameRule>, val rites: List<RiteStatus>, val reached: Boolean, val current: Boolean)

/**
 * The walk the Star level sets a star in: Hollywood's, on the Boulevard and
 * Vine Street, or, for a taste of Bollywood alone, Bandra's Walk of the
 * Stars on the Bandstand, with the page that tells about it.
 */
data class WalkOfFame(val name: String, val place: String, val link: String, val blurb: String, val town: String)

/** The levels page: the user's progress, the ladder, the walk its star goes in, every game's rule, and the posters of their latest watched movies, for the wall. */
data class LevelsView(val progress: LevelProgress, val rungs: List<LevelRung>, val walk: WalkOfFame, val rules: List<GameRule>, val posters: List<Movie>) {
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
    private val checkInRepository: CheckInRepository,
    private val userBadgeRepository: UserBadgeRepository,
    private val friendInviteRepository: FriendInviteRepository,
    private val clubInviteRepository: ClubInviteRepository
) {

    /** Where the user stands now, their stored level raised when the score reached a new one. */
    suspend fun progressOf(user: User): LevelProgress = transaction { progressIn(user) }

    /** The same, inside a transaction of the caller's. */
    fun progressIn(user: User): LevelProgress {
        val watched = movieSeenRepository.countFor(user)
        val checkedIn = checkInRepository.countMovies(user)
        val score = watched + CHECK_IN_EXTRA * checkedIn
        val byPoints = Level.of(score)
        // The points reach a level; the rites of every level up to it must be met too, each in turn.
        val earned = userBadgeRepository.findAll(user).map { it.badge }.toSet()
        val invited = invitedBy(user)
        val reached = Level.entries.takeWhile { it.ordinal <= byPoints.ordinal && LevelRites.met(it, earned, invited) }.last()
        // A level once reached is kept: the stored one is raised, never lowered.
        val level = if (reached.reaches(user.level)) reached else user.level
        if (level != user.level) userRepository.update(userRepository.getById(user.id).copy(level = level))
        return LevelProgress(level, score, watched, checkedIn, byPoints, level.next?.let { LevelRites.statusOf(it, earned, invited) }.orEmpty())
    }

    /** How many people came through the user's invites, friends and clubs together. */
    private fun invitedBy(user: User): Int =
        friendInviteRepository.findAll(FriendInvite_.inviter eq user).sumOf { it.acceptedCount } +
            clubInviteRepository.findAll(ClubInvite_.inviter eq user).sumOf { it.acceptedCount }

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
        val earned = userBadgeRepository.findAll(user).map { it.badge }.toSet()
        val invited = invitedBy(user)
        LevelsView(
            progress,
            Level.entries.map { LevelRung(it, GameRules.unlockedAt(it), LevelRites.statusOf(it, earned, invited), progress.level.reaches(it), it == progress.level) },
            walkOf(user),
            GameRules.named,
            movieSeenRepository.findMostRecent(user, WALL_POSTERS).map { it.movie }
        )
    }

    /**
     * The walk the user's star goes in: Bandra's for a taste that keeps
     * Bollywood, Hollywood hidden or Hindi among the languages, Hollywood's
     * for everyone else.
     */
    fun walkOf(user: User): WalkOfFame {
        val filter = user.movieFilter
        val hidden = filter?.hiddenTags.orEmpty()
        val bollywood = Tag.BOLLYWOOD !in hidden && (Tag.HOLLYWOOD in hidden || "hi" in filter?.languages.orEmpty())
        return if (bollywood) BANDRA else HOLLYWOOD
    }

    companion object {
        val HOLLYWOOD = WalkOfFame(
            "Hollywood Walk of Fame", "Hollywood Boulevard, at the corner of Vine Street",
            "https://en.wikipedia.org/wiki/Hollywood_Walk_of_Fame",
            "Five hundred movies: your own star in the terrazzo, somewhere along Hollywood Boulevard, a few blocks from Vine.",
            "Hollywood"
        )
        val BANDRA = WalkOfFame(
            "Walk of the Stars", "Bandstand Promenade, Bandra, Mumbai",
            "https://en.wikipedia.org/wiki/Walk_of_the_Stars",
            "Five hundred movies: your own star on the Bandstand in Bandra, where Bollywood takes its evening walk by the sea.",
            "Bollywood"
        )
        /** What a check-in adds to a watched movie's one point. */
        const val CHECK_IN_EXTRA = 0.5
        private const val WALL_POSTERS = 36
    }
}
