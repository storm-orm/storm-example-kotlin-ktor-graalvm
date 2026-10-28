package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.Invite
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.YearShare
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GenreSeenCount
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.PersonSeenCount
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.YearShareRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A month of a year in movies: its short name and how many check-ins it had. */
data class MonthCount(val month: String, val checkIns: Long)

/** The longest run of days in a row with a check-in, from the first day to the last. */
data class Streak(val days: Int, val from: LocalDate, val until: LocalDate) {
    /** The run as the page says it: "12 – 18 Mar". */
    val caption: String
        get() = (if (from.month == until.month) "${from.dayOfMonth}" else DAY_MONTH.format(from)) + " – " + DAY_MONTH.format(until)
}

/** A country or a language of a year, by its name, with its flag where it is a country. */
data class Place(val name: String, val flag: String?, val movies: Long)

/** Where a year stands among the friends': a friend's check-ins, or the user's own ([you]); [share] is the bar's width. */
data class YearRank(val name: String, val avatarUrl: String?, val checkIns: Long, val you: Boolean, val share: Double)

/**
 * A user's year in movies, from their check-ins of that year: how much they
 * watched and when, what and whose, from where, the movies that stand out,
 * and how the year compares with their friends'. [isEmpty] while the year
 * has no check-ins.
 */
data class YearInMovies(
    val year: Int,
    val owner: User,
    /** The years there is something to look back on, the latest first, for the switch between them. */
    val years: List<Int>,
    val checkIns: Int,
    val movies: Int,
    val hours: Long,
    /** The check-ins of a movie checked in before, that year or an earlier one. */
    val rewatches: Int,
    val averageRating: Double?,
    val months: List<Bar<MonthCount>>,
    val streak: Streak?,
    val favouriteWeekday: String?,
    val genres: List<Bar<GenreSeenCount>>,
    val actors: List<PersonSeenCount>,
    val directors: List<PersonSeenCount>,
    val countries: List<Place>,
    val languages: List<Place>,
    val standouts: List<Highlight>,
    /** The friends who share what they watch and when, with the user, the most check-ins first; empty without such friends. */
    val friends: List<YearRank>,
    /** The year's movies for the posters across the top, the latest first. */
    val posters: List<Movie>
) {
    val isEmpty: Boolean get() = checkIns == 0
    val firstWatches: Int get() = checkIns - rewatches
    /** How often the posters go round in the strip: an even number, so its halves match, and enough to fill it. */
    val posterRounds: Int get() = if (posters.isEmpty()) 0 else 2 * ((POSTER_FILL + posters.size - 1) / posters.size)
    val busiestMonth: MonthCount? get() = months.maxByOrNull { it.count }?.takeIf { it.count > 0 }?.item

    /** The line a shared year unfurls with: "48 movies · 96 hours · mostly Drama". */
    val caption: String
        get() = listOfNotNull(
            if (movies == 1) "1 movie" else "$movies movies",
            "$hours hours".takeIf { hours > 0 },
            genres.firstOrNull()?.let { "mostly ${it.item.genre.name}" }
        ).joinToString(" · ")
}

/** The posters the strip across the top of a year needs to span a wide screen. */
private const val POSTER_FILL = 12

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * A year in movies: everything is read from the check-ins of that year,
 * the days in UTC, as the check-ins show them. The counts per genre,
 * person, country and language are aggregate queries joined through the
 * check-ins of the year; the months, the streak and the standouts are
 * worked out from the check-ins read. A year can be shared with a link
 * ([YearShare]), which shows it without the friends.
 */
class WrappedService(
    private val checkInRepository: CheckInRepository,
    private val ratingRepository: RatingRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val principalRepository: PrincipalRepository,
    private val movieCountryRepository: MovieCountryRepository,
    private val movieLanguageRepository: MovieLanguageRepository,
    private val friendshipRepository: FriendshipRepository,
    private val yearShareRepository: YearShareRepository
) {

    /** The user's own year, compared with their friends'. */
    suspend fun yearOf(user: User, year: Int): YearInMovies = transaction(readOnly = true) {
        read(user, year, withFriends = true)
    }

    /** A year someone shared, by its link's token, without their friends; null for a link that names none. */
    suspend fun shared(token: String): YearInMovies? = transaction(readOnly = true) {
        yearShareRepository.findById(token)?.let { read(it.user, it.year, withFriends = false) }
    }

    /** The token of the link the user shares their year with: the one made the first time they shared it. */
    suspend fun share(user: User, year: Int, now: Instant = Instant.now()): String = transaction {
        yearShareRepository.findShare(user, year)?.token
            ?: Invite.newToken().also { yearShareRepository.insert(YearShare(token = it, user = user, year = year, createdAt = now)) }
    }

    private fun read(user: User, year: Int, withFriends: Boolean): YearInMovies {
        val from = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val until = LocalDate.of(year + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val firsts = checkInRepository.findFirstPerMovie(user).associate { it.movie.entityId() to it.watchedAt }
        val checkIns = checkInRepository.findBetween(user, from, until)
        val years = yearsOf(firsts.values, year)
        if (checkIns.isEmpty()) return nothingWatched(user, year, years)
        val movies = checkIns.map { it.movie }.distinctBy { it.id }
        val ratings = ratingRepository.findByMovies(movies.map { it.ref() })
        val favourites = principalRepository.findCheckedInFavourites(user, from, until, MINIMUM_MOVIES, FAVOURITES_LIMIT)
        return YearInMovies(
            year = year,
            owner = user,
            years = years,
            checkIns = checkIns.size,
            movies = movies.size,
            hours = checkIns.sumOf { it.movie.runtimeMinutes ?: 0 }.toLong() / 60,
            rewatches = checkIns.count { it.watchedAt.isAfter(firsts.getValue(it.movie.id)) },
            averageRating = ratings.map { it.averageRating.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
            months = bars(monthsOf(checkIns)) { it.checkIns },
            streak = streakOf(checkIns),
            favouriteWeekday = weekdayOf(checkIns),
            genres = bars(movieGenreRepository.countCheckedInPerGenre(user, from, until, GENRE_LIMIT)) { it.movieCount },
            actors = favourites.actors,
            directors = favourites.directors,
            countries = movieCountryRepository.countCheckedIn(user, from, until).take(PLACE_LIMIT)
                .map { Place(Countries.of(it.country)?.name ?: it.country, flagOf(it.country), it.movies) },
            languages = movieLanguageRepository.countCheckedIn(user, from, until).take(PLACE_LIMIT)
                .map { Place(Languages.nameOf(it.language), null, it.movies) },
            standouts = standouts(checkIns, ratings),
            friends = if (withFriends) friendsOf(user, from, until) else emptyList(),
            posters = movies.reversed().take(POSTER_LIMIT)
        )
    }

    private fun nothingWatched(user: User, year: Int, years: List<Int>) = YearInMovies(
        year, user, years, 0, 0, 0, 0, null, emptyList(), null, null, emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
    )

    /** The years from the first check-in to this one, the latest first, with the year asked for among them. */
    private fun yearsOf(firsts: Collection<Instant>, year: Int): List<Int> {
        val now = LocalDate.now(ZoneOffset.UTC).year
        val first = firsts.minOrNull()?.atZone(ZoneOffset.UTC)?.year ?: now
        return (minOf(first, year)..maxOf(now, year)).reversed().toList()
    }

    /** The check-ins of each month, all twelve, January first. */
    private fun monthsOf(checkIns: List<CheckIn>): List<MonthCount> {
        val counts = checkIns.groupingBy { it.watchedAt.atZone(ZoneOffset.UTC).month }.eachCount()
        return Month.entries.map { MonthCount(it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), (counts[it] ?: 0).toLong()) }
    }

    /** The longest run of days in a row with a check-in; none shorter than two days. */
    private fun streakOf(checkIns: List<CheckIn>): Streak? {
        val days = checkIns.map { it.watchedAt.atZone(ZoneOffset.UTC).toLocalDate() }.distinct().sorted()
        var best: Streak? = null
        var start = 0
        for (i in days.indices) {
            if (i > 0 && days[i] != days[i - 1].plusDays(1)) start = i
            val length = i - start + 1
            if (length >= 2 && length > (best?.days ?: 0)) best = Streak(length, days[start], days[i])
        }
        return best
    }

    /** The day of the week with the most check-ins, when one day is ahead of the others with more than one. */
    private fun weekdayOf(checkIns: List<CheckIn>): String? {
        val counts = checkIns.groupingBy { it.watchedAt.atZone(ZoneOffset.UTC).dayOfWeek }.eachCount()
        val most = counts.values.max()
        return counts.filterValues { it == most }.keys.singleOrNull()?.takeIf { most > 1 }
            ?.let { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s" }
    }

    /**
     * The movies of the year that stand out, each once, under the first kind
     * it tops: the first and the last of the year, the one watched most
     * often, the best rated, the least known, the oldest, the longest and
     * the lowest rated, then the newest and the shortest to fill the row.
     */
    private fun standouts(checkIns: List<CheckIn>, ratings: List<Rating>): List<Highlight> {
        val day = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).withZone(ZoneOffset.UTC)
        val watches = checkIns.groupingBy { it.movie.id }.eachCount()
        val movies = checkIns.map { it.movie }.distinctBy { it.id }
        val withYear = movies.filter { it.startYear != null }
        val withRuntime = movies.filter { it.runtimeMinutes != null }
        return listOfNotNull(
            checkIns.first().let { Highlight("First of the year", it.movie, day.format(it.watchedAt)) },
            checkIns.last().let { Highlight("Last one in", it.movie, day.format(it.watchedAt)) }.takeIf { checkIns.size > 1 },
            watches.maxBy { it.value }.takeIf { it.value > 1 }?.let { (id, count) -> Highlight("On repeat", movies.first { it.id == id }, "Watched $count times") },
            ratings.maxByOrNull { it.averageRating }?.let { Highlight("Best rated", it.movie, "★ ${it.averageRating} on IMDB") },
            ratings.minByOrNull { it.voteCount }?.let { Highlight("Hidden gem", it.movie, "${VOTES.format(it.voteCount)} votes") },
            withYear.minByOrNull { it.startYear!! }?.let { Highlight("Oldest", it, "From ${it.startYear}") },
            withRuntime.maxByOrNull { it.runtimeMinutes!! }?.let { Highlight("Longest", it, "${it.runtimeMinutes} min") },
            ratings.minByOrNull { it.averageRating }?.let { Highlight("Lowest rated", it.movie, "★ ${it.averageRating} on IMDB") },
            withYear.maxByOrNull { it.startYear!! }?.let { Highlight("Newest", it, "From ${it.startYear}") },
            withRuntime.minByOrNull { it.runtimeMinutes!! }?.let { Highlight("Shortest", it, "${it.runtimeMinutes} min") }
        ).distinctBy { it.movie.id }.take(STANDOUT_LIMIT)
    }

    /**
     * The user's year beside their friends': the check-ins of each friend
     * who shares what they watch and when, as far as they share their name.
     */
    private fun friendsOf(user: User, from: Instant, until: Instant): List<YearRank> {
        val friends = friendshipRepository.findFriends(user).map { it.friend }.filter { it.shareSeen && it.shareDates }
        if (friends.isEmpty()) return emptyList()
        val counts = checkInRepository.countBetween(friends + user, from, until).associate { it.user.entityId() to it.checkIns }
        val ranks = (friends + user).map { person ->
            val you = person.id == user.id
            YearRank(
                name = if (you) "You" else person.name.takeIf { person.shareName } ?: "A friend",
                avatarUrl = person.avatarUrl.takeIf { you || person.shareName },
                checkIns = counts[person.id] ?: 0,
                you = you,
                share = 0.0
            )
        }.sortedWith(compareByDescending<YearRank> { it.checkIns }.thenBy { !it.you })
        val most = ranks.first().checkIns.coerceAtLeast(1)
        return ranks.map { it.copy(share = 100.0 * it.checkIns / most) }
    }

    private fun <T> bars(items: List<T>, count: (T) -> Long): List<Bar<T>> {
        val longest = items.maxOfOrNull(count)?.takeIf { it > 0 } ?: 1L
        return items.map { Bar(it, count(it), 100.0 * count(it) / longest) }
    }

    companion object {
        /** A favourite of the year is in at least this many of its movies. */
        private const val MINIMUM_MOVIES = 2
        private const val FAVOURITES_LIMIT = 5
        private const val GENRE_LIMIT = 6
        private const val PLACE_LIMIT = 8
        private const val STANDOUT_LIMIT = 6
        private const val POSTER_LIMIT = 14
        private val VOTES: NumberFormat = NumberFormat.getIntegerInstance(Locale.ENGLISH)

        /** A country's flag: its two letters as regional indicator symbols, which the emoji fonts draw as the flag. */
        fun flagOf(code: String): String? =
            code.uppercase().takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
                ?.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }?.joinToString("")
    }
}
