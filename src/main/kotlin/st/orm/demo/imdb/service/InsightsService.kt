package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.DecadeMovieCount
import st.orm.demo.imdb.repository.GenreSeenCount
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.PersonSeenCount
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.transaction
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/** One bar of the rating histogram: the seen movies rated from [from] up to half a point more. */
data class RatingBin(val from: Double, val count: Int, val height: Double)

/**
 * The IMDB ratings of the seen movies as a histogram in half points, with
 * the bell curve of the range the recommendations use ([RatingRange]) laid
 * over it. The bars and the curve share one scale: the heights are in
 * percent of the chart, and the curve is the points of an SVG polyline in a
 * 100 by 100 box, the count the range expects per half point.
 */
data class RatingDistribution(
    val bins: List<RatingBin>,
    val range: RatingRange?,
    val curve: String?
) {
    val from: Double get() = bins.first().from
    val to: Double get() = bins.last().from + BIN

    /** The labels under the chart: every whole point. */
    val ticks: List<Int> get() = (ceil(from).toInt()..floor(to).toInt()).toList()

    /** The range as the page says it: one spread either side of the mean. */
    val usualFrom: Double? get() = range?.let { it.mean - it.spread }
    val usualTo: Double? get() = range?.let { it.mean + it.spread }

    /** Where a rating lies along the chart, in percent of its width: for the labels and the band of the usual range. */
    fun position(rating: Double): Double = (100.0 * (rating - from) / (to - from)).coerceIn(0.0, 100.0)

    companion object {
        const val BIN = 0.5
        private const val CURVE_POINTS = 60

        /** The chart spans the seen ratings, never less than five to nine, so a narrow taste still shows as one. */
        fun of(ratings: List<Rating>): RatingDistribution? {
            if (ratings.isEmpty()) return null
            val values = ratings.map { it.averageRating.toDouble() }
            val from = floor(minOf(values.min(), 5.0))
            val to = ceil(maxOf(values.max(), 9.0)).coerceAtMost(10.0)
            val starts = generateSequence(from) { it + BIN }.takeWhile { it < to }.toList()
            // A rating falls in the bin it starts; a 10 in the last one.
            val counts = IntArray(starts.size)
            values.forEach { counts[minOf(((it - from) / BIN).toInt(), starts.lastIndex)]++ }
            val range = RatingRange.of(ratings)
            val expected = range?.let { r -> { rating: Double -> values.size * BIN * normal(rating, r) } }
            val peak = maxOf(counts.max().toDouble(), expected?.invoke(range.mean) ?: 0.0)
            val bins = starts.mapIndexed { i, start -> RatingBin(start, counts[i], 100.0 * counts[i] / peak) }
            val curve = expected?.let { density ->
                (0..CURVE_POINTS).joinToString(" ") { step ->
                    val rating = from + (to - from) * step / CURVE_POINTS
                    "%.2f,%.2f".format(Locale.ROOT, 100.0 * step / CURVE_POINTS, 100.0 - 100.0 * density(rating) / peak)
                }
            }
            return RatingDistribution(bins, range, curve)
        }

        private fun normal(rating: Double, range: RatingRange): Double {
            val spreads = (rating - range.mean) / range.spread
            return exp(-spreads * spreads / 2) / (range.spread * sqrt(2 * PI))
        }
    }
}

/** A seen movie that stands out, and why: the card the insights page shows for it. */
data class Highlight(val label: String, val movie: Movie, val detail: String)

/** A count as a bar: [share] is the bar's width in percent of the longest. */
data class Bar<T>(val item: T, val count: Long, val share: Double)

/** Everything the insights page shows about a visitor; [isEmpty] while nothing is seen. */
data class InsightsView(
    val seenCount: Int,
    val watchlistCount: Long,
    val likedCount: Int,
    val dislikedCount: Int,
    val hoursWatched: Long,
    val averageRating: Double?,
    val ratings: RatingDistribution?,
    val genres: List<Bar<GenreSeenCount>>,
    val decades: List<Bar<DecadeMovieCount>>,
    val actors: List<PersonSeenCount>,
    val directors: List<PersonSeenCount>,
    val highlights: List<Highlight>,
    /** How the thumbs compare with IMDB: the average IMDB rating of the liked and of the disliked movies, when both exist. */
    val likedAverage: Double?,
    val dislikedAverage: Double?
) {
    val isEmpty: Boolean get() = seenCount == 0
    val favouriteDecade: Int? get() = decades.maxByOrNull { it.count }?.item?.decade

    companion object {
        /** The page of a visitor who has seen nothing yet: it asks them to mark what they saw. */
        fun nothingSeen(watchlistCount: Long) = InsightsView(
            0, watchlistCount, 0, 0, 0, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, null
        )
    }
}

/**
 * What a visitor's seen list says about them: favourite actors, directors
 * and genres, the decades, the spread of the ratings they watch, and the
 * movies that stand out. The counts are aggregate queries joined through
 * the seen list (GROUP BY, HAVING), the rest is read from the seen movies
 * and their ratings, one query each, in one read-only transaction.
 */
class InsightsService(
    private val movieSeenRepository: MovieSeenRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val principalRepository: PrincipalRepository
) {

    /**
     * The visitor's insights, read in one read-only transaction: the seen
     * movies with their ratings in one query, the genres, the favourite
     * people in one count, and the watchlist size. The totals, the decades,
     * the rating spread and the standouts are worked out from the seen
     * movies already read.
     */
    suspend fun insightsFor(user: User): InsightsView = transaction(readOnly = true) {
        val rows = movieSeenRepository.findAllWithRatings(user)
        if (rows.isEmpty()) return@transaction InsightsView.nothingSeen(watchlistRepository.countFor(user))
        val seen = rows.map { it.seen }
        val ratings = rows.mapNotNull { it.rating }
        val ratingsByMovie = ratings.associateBy { it.movie.id }
        val favourites = principalRepository.findSeenFavourites(user, MINIMUM_MOVIES, FAVOURITES_LIMIT)
        fun averageOf(movies: List<Movie>) = movies.mapNotNull { ratingsByMovie[it.id]?.averageRating?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
        val liked = seen.filter { it.liked == true }.map { it.movie }
        val disliked = seen.filter { it.liked == false }.map { it.movie }
        InsightsView(
            seenCount = seen.size,
            watchlistCount = watchlistRepository.countFor(user),
            likedCount = liked.size,
            dislikedCount = disliked.size,
            hoursWatched = seen.sumOf { it.movie.runtimeMinutes ?: 0 }.toLong() / 60,
            averageRating = averageOf(seen.map { it.movie }),
            ratings = RatingDistribution.of(ratings),
            genres = bars(movieGenreRepository.countSeenPerGenre(user, GENRE_LIMIT)) { it.movieCount },
            decades = bars(decadesOf(seen)) { it.movieCount },
            actors = favourites.actors,
            directors = favourites.directors,
            highlights = highlights(seen, ratings),
            likedAverage = averageOf(liked).takeIf { disliked.isNotEmpty() },
            dislikedAverage = averageOf(disliked).takeIf { liked.isNotEmpty() }
        )
    }

    /** How many of the seen movies came out in each decade, the earliest first. */
    private fun decadesOf(seen: List<MovieSeen>): List<DecadeMovieCount> =
        seen.mapNotNull { it.movie.startYear?.let(::decadeOf) }
            .groupingBy { it }.eachCount()
            .toSortedMap()
            .map { (decade, count) -> DecadeMovieCount(decade, count.toLong()) }

    private fun <T> bars(items: List<T>, count: (T) -> Long): List<Bar<T>> {
        val longest = items.maxOfOrNull(count)?.takeIf { it > 0 } ?: 1L
        return items.map { Bar(it, count(it), 100.0 * count(it) / longest) }
    }

    /**
     * The seen movies that stand out, six at most, each the extreme of its
     * kind: the best and the lowest rated, the least known and the most
     * voted, the longest and the oldest, then the newest, the shortest and
     * the one marked last. A movie stands out once, under the first kind it
     * tops; a kind it also tops is left out, and the next kind fills in.
     */
    private fun highlights(seen: List<MovieSeen>, ratings: List<Rating>): List<Highlight> {
        if (ratings.size < 2) return emptyList()
        val withRuntime = ratings.filter { it.movie.runtimeMinutes != null }
        val withYear = ratings.filter { it.movie.startYear != null }
        val lastMarked = seen.maxBy { it.seenAt }.movie.id
        return listOfNotNull(
            ratings.maxBy { it.averageRating }.let { Highlight("Highest rated", it.movie, "★ ${it.averageRating} on IMDB") },
            ratings.minBy { it.averageRating }.let { Highlight("Lowest rated", it.movie, "★ ${it.averageRating} on IMDB") },
            ratings.minBy { it.voteCount }.let { Highlight("Hidden gem", it.movie, "${VOTES.format(it.voteCount)} votes, the fewest of yours") },
            ratings.maxBy { it.voteCount }.let { Highlight("Crowd favourite", it.movie, "${VOTES.format(it.voteCount)} votes") },
            withRuntime.maxByOrNull { it.movie.runtimeMinutes!! }?.let { Highlight("Longest", it.movie, "${it.movie.runtimeMinutes} min") },
            withYear.minByOrNull { it.movie.startYear!! }?.let { Highlight("Oldest", it.movie, "From ${it.movie.startYear}") },
            withYear.maxByOrNull { it.movie.startYear!! }?.let { Highlight("Newest", it.movie, "From ${it.movie.startYear}") },
            withRuntime.minByOrNull { it.movie.runtimeMinutes!! }?.let { Highlight("Shortest", it.movie, "${it.movie.runtimeMinutes} min") },
            ratings.firstOrNull { it.movie.id == lastMarked }?.let { Highlight("Seen last", it.movie, "The latest you marked") }
        ).distinctBy { it.movie.id }.take(HIGHLIGHT_LIMIT)
    }

    companion object {
        /** A favourite is on at least this many of the visitor's movies. */
        private const val MINIMUM_MOVIES = 2
        /** The favourites and the genres stand side by side on the page: the same length keeps them level. */
        private const val FAVOURITES_LIMIT = 8
        private const val GENRE_LIMIT = 8

        /** The standouts fill one row of the page. */
        private const val HIGHLIGHT_LIMIT = 6
        private val VOTES: NumberFormat = NumberFormat.getIntegerInstance(Locale.ENGLISH)
    }
}
