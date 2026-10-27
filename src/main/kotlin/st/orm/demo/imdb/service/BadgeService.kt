package st.orm.demo.imdb.service

import java.util.concurrent.ConcurrentHashMap
import st.orm.demo.imdb.model.Badge
import st.orm.demo.imdb.model.BadgeCategory
import st.orm.demo.imdb.model.DailyGame
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.KEVIN_BACON
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserBadge
import st.orm.demo.imdb.model.UserBadge_
import st.orm.demo.imdb.repository.CastAnswerRepository
import st.orm.demo.imdb.repository.CastBidRepository
import st.orm.demo.imdb.repository.ChainLinkRepository
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.DailyPlayRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.MovieNightRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.SwipeRepository
import st.orm.demo.imdb.repository.SwipeRoundSwipeRepository
import st.orm.demo.imdb.repository.UserBadgeRepository
import st.orm.template.entityId
import st.orm.template.eq
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/** Where a user stands with a badge: how far along, and when they earned it, if they did. */
data class BadgeProgress(val badge: Badge, val done: Int, val earnedAt: Instant?) {
    val earned: Boolean get() = earnedAt != null
    /** The way to the goal, from 0 to 1. */
    val fraction: Double get() = if (earned) 1.0 else (done.toDouble() / badge.goal).coerceIn(0.0, 1.0)
    /** "3 of 10", or "Earned". */
    val text: String get() = if (earned) "Earned" else "${done.coerceAtMost(badge.goal)} of ${badge.goal}"
}

/** A category's badges on the wall, the earned first. */
data class BadgeShelf(val category: BadgeCategory, val badges: List<BadgeProgress>) {
    val earnedCount: Int get() = badges.count { it.earned }
}

/** The badge wall: every badge by category, and how many are earned. */
data class BadgeWall(val shelves: List<BadgeShelf>) {
    val earnedCount: Int get() = shelves.sumOf { it.earnedCount }
    val total: Int get() = shelves.sumOf { it.badges.size }
}

/**
 * The badges: each counts something the site knows, watching, check-ins,
 * the games or the company, and is earned at its goal. The wall is
 * worked out whenever it is looked at; a badge earned is stored then,
 * with its time, and kept.
 */
class BadgeService(
    private val userBadgeRepository: UserBadgeRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val movieLanguageRepository: MovieLanguageRepository,
    private val checkInRepository: CheckInRepository,
    private val chainLinkRepository: ChainLinkRepository,
    private val dailyPlayRepository: DailyPlayRepository,
    private val castBidRepository: CastBidRepository,
    private val castAnswerRepository: CastAnswerRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val movieNightRepository: MovieNightRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val swipeRepository: SwipeRepository,
    private val swipeRoundSwipeRepository: SwipeRoundSwipeRepository
) {
    /** When each user's badges were last awarded, for the pages that award now and then. */
    private val awarded = ConcurrentHashMap<String, Instant>()

    /** Awards the user's badges unless that was done within the last little while: for the pages seen often. */
    suspend fun awardIfDue(user: User, now: Instant = Instant.now()): List<Badge> {
        val last = awarded[user.id]
        if (last != null && Duration.between(last, now) < AWARD_EVERY) return emptyList()
        awarded[user.id] = now
        return award(user, now)
    }


    /** The user's wall, the badges reached since last time stored as earned now. */
    suspend fun wallOf(user: User, now: Instant = Instant.now()): BadgeWall = transaction {
        val progress = progressIn(user, now)
        BadgeWall(BadgeCategory.entries.map { category ->
            BadgeShelf(category, progress.filter { it.badge.category == category }.sortedWith(compareByDescending<BadgeProgress> { it.earned }.thenBy { it.badge.ordinal }))
        })
    }

    /** The badges the user earned since they were last looked at, stored now: what a page announces. */
    suspend fun award(user: User, now: Instant = Instant.now()): List<Badge> = transaction {
        val before = userBadgeRepository.findAll(user).map { it.badge }.toSet()
        progressIn(user, now).filter { it.earned && it.badge !in before }.map { it.badge }
    }

    /** How many badges the user has earned, as stored: the Play page reads this, the levels page and the check-ins award. */
    suspend fun countOf(user: User): Int = transaction(readOnly = true) { userBadgeRepository.count(UserBadge_.user eq user).toInt() }

    /** Every badge's progress, inside the caller's transaction, the ones newly reached stored. */
    private fun progressIn(user: User, now: Instant): List<BadgeProgress> {
        val earned = userBadgeRepository.findAll(user).associate { it.badge to it.earnedAt }.toMutableMap()
        val counts = countsOf(user, now)
        return Badge.entries.map { badge ->
            val done = counts[badge] ?: 0
            if (done >= badge.goal && badge !in earned) {
                userBadgeRepository.insert(UserBadge(user, badge, now))
                earned[badge] = now
            }
            BadgeProgress(badge, done, earned[badge])
        }
    }

    /** How far the user is with each badge, as what it counts. */
    private fun countsOf(user: User, now: Instant): Map<Badge, Int> {
        val counts = mutableMapOf<Badge, Int>()
        // Watching: the decades, the genres, the languages.
        val years = movieSeenRepository.findAll(user).mapNotNull { it.movie.startYear }
        counts[Badge.FIRST_REEL] = years.size.coerceAtLeast(movieSeenRepository.countFor(user).toInt())
        counts[Badge.GOLDEN_AGE] = years.count { it < 1970 }
        counts[Badge.NEW_HOLLYWOOD] = years.count { it in 1970..1989 }
        counts[Badge.NINETIES] = years.count { it in 1990..1999 }
        counts[Badge.THIS_CENTURY] = years.count { it >= 2000 }
        val genres = movieGenreRepository.countSeenPerGenre(user, 50).associate { it.genre.name to it.movieCount.toInt() }
        counts[Badge.HORROR_HOUND] = genres["Horror"] ?: 0
        counts[Badge.ANIMATED] = genres["Animation"] ?: 0
        counts[Badge.DOCUMENTARIAN] = genres["Documentary"] ?: 0
        counts[Badge.LAUGH_TRACK] = genres["Comedy"] ?: 0
        counts[Badge.FAR_OUT] = genres["Sci-Fi"] ?: 0
        counts[Badge.WORLD_CINEMA] = movieLanguageRepository.countSeen(user).size
        // Checking in: the days, and the weeks in a row.
        val days = checkInRepository.findAll(user).map { LocalDate.ofInstant(it.watchedAt, ZoneOffset.UTC) }
        counts[Badge.CHECKED_IN] = days.size
        counts[Badge.NIGHT_SHIFT] = days.size
        counts[Badge.DOUBLE_BILL] = days.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        counts[Badge.REGULAR] = longestRun(days.map { it.get(IsoFields.WEEK_BASED_YEAR) * 100L + it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }.toSortedSet().toList()) { a, b -> next(a) == b }
        // Playing.
        val links = chainLinkRepository.findByPlayer(user)
        counts[Badge.BACON_NUMBER] = links.count { it.via?.id == KEVIN_BACON }
        counts[Badge.CHAIN_GANG] = links.groupingBy { it.match.entityId() }.eachCount().values.maxOrNull() ?: 0
        val dailies = dailyPlayRepository.findAll(user).filter { it.finished }
        counts[Badge.PERFECT_GRID] = dailies.count { it.id.game == DailyGame.CONNECTIONS && it.score == 4 && it.mistakes == 0 }
        counts[Badge.ODD_EYE] = dailies.count { it.id.game == DailyGame.ODD_ONE_OUT && it.score == DailyPuzzleService.ODD_PUZZLES }
        counts[Badge.DAILY_WEEK] = longestRun(dailies.map { it.id.puzzleOn }.toSortedSet().toList()) { a, b -> a.plusDays(1) == b }
        counts[Badge.FIRST_DAILY] = dailies.size
        counts[Badge.DECK_DONE] = swipeRepository.findAll(user).size + swipeRoundSwipeRepository.findByUser(user).size
        val played = gamePlayerRepository.findAllOf(user)
        counts[Badge.HOT_STREAK] = played.filter { it.match.game == Game.HIGHER_LOWER }.maxOfOrNull { it.score } ?: 0
        // A win: a Higher or Lower or Movie Chain with others that is over, the user's score above everyone else's.
        val wins = played.filter { it.match.mode != GameMode.SOLO && it.match.game in SCORED_GAMES && it.match.isOver(now) }
            .count { me -> gamePlayerRepository.findPlayers(me.match).all { it.user.id == me.user.id || it.score < me.score } }
        counts[Badge.WINNER] = wins
        counts[Badge.CHAMPION] = wins
        val rightByMatch = castAnswerRepository.findByUser(user).filter { it.right }.groupingBy { it.match.entityId() }.eachCount()
        counts[Badge.CAST_CALL] = castBidRepository.findByUser(user).count { it.count >= CAST_CALL_BID && (rightByMatch[it.match.entityId()] ?: 0) >= it.count }
        // Together.
        counts[Badge.FIRST_FRIEND] = friendshipRepository.findFriends(user).size
        counts[Badge.CLUB_MEMBER] = clubMemberRepository.findClubs(user).size
        counts[Badge.NIGHT_HOST] = movieNightRepository.countHostedBy(user).toInt()
        return counts
    }

    /** The week after a week in the year-week form, the first of the next year after the last. */
    private fun next(week: Long): Long {
        val year = week / 100
        val number = week % 100
        val weeksInYear = LocalDate.of(year.toInt(), 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return if (number < weeksInYear) week + 1 else (year + 1) * 100 + 1
    }

    /** The longest run of items in a row, as [follows] says, in a sorted list. */
    private fun <T> longestRun(sorted: List<T>, follows: (T, T) -> Boolean): Int {
        var best = 0
        var run = 0
        var previous: T? = null
        for (item in sorted) {
            run = if (previous != null && follows(previous, item)) run + 1 else 1
            best = maxOf(best, run)
            previous = item
        }
        return best
    }

    companion object {
        /** The bid a Name That Cast round has to be won at for its badge. */
        const val CAST_CALL_BID = 10
        /** The games a win counts in: the ones with a score to compare. */
        private val SCORED_GAMES = setOf(Game.HIGHER_LOWER, Game.CHAIN)
        private val AWARD_EVERY: Duration = Duration.ofMinutes(10)
        private val DAY: ChronoUnit = ChronoUnit.DAYS
    }
}
