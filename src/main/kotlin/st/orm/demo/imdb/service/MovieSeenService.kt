package st.orm.demo.imdb.service

import st.orm.Window
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.RecommendationEventKind
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.entityId
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneOffset

/** The seen page: how many movies the user has seen, and the first window of them. */
data class SeenView(
    val count: Long,
    val window: Window<MovieSeen>
)

/** The check-ins page: the first window of check-ins, the latest first, under their month. */
data class CheckInsView(
    val window: Window<CheckIn>
) {
    /** The check-ins of the window under their month; each movie's tag shows its day. */
    val months: List<MovieGroup> get() = window.content().groupedBy(label = { monthLabel(it.watchedAt) }, movie = { it.movie })
}

/**
 * What an IMDb import did: the movies it marked as seen, the ones seen
 * already, the check-ins it recorded, and the titles the catalogue does not
 * hold (series, episodes, and movies with too few votes).
 */
data class HistoryImport(val added: Int, val updated: Int, val checkIns: Int, val skipped: Int)

/**
 * A movie being seen, and its check-ins. Seen is one row per movie
 * ([MovieSeen]), with the vote; each watch on a day is a [CheckIn], so a
 * movie watched twice has two. A seen movie without check-ins is history,
 * seen at some time before, and shows no day.
 */
class MovieSeenService(
    private val movieRepository: MovieRepository,
    private val userRepository: UserRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val checkInRepository: CheckInRepository,
    private val recommendationService: RecommendationService,
    private val movieSourceService: MovieSourceService
) {

    /**
     * Marks the movie as seen by the user, or unmarks it when already seen,
     * its check-ins with it, and returns the state of both lists afterwards.
     * Marked as seen before ([historic]), the movie gets no check-in: the
     * movie page's Mark as seen and the catch-up page mark that way, and
     * [checkIn] records a watch on a day. A movie just seen comes off the
     * watchlist: that is what a list of movies to watch means. Unmarking does
     * not put it back. Either way the user's recommendations are recomputed
     * in the background.
     *
     * Like the watchlist toggle, the cycle runs in the route's
     * `transactional { }` block, so the mark and the watchlist removal are
     * one transaction without transaction code here.
     */
    suspend fun toggle(user: User, movieId: String, historic: Boolean = false): MovieListState {
        val movie = movieRepository.getById(movieId)
        val now = Instant.now()
        val stored = userRepository.touch(user, now)
        val state = if (movieSeenRepository.contains(stored, movie)) {
            checkInRepository.removeAll(stored, movie)
            movieSeenRepository.removeById(MovieSeenPk(userId = stored.id, movieId = movie.id))
            MovieListState.of(onWatchlist = watchlistRepository.contains(stored, movie), seen = null, checkedInAt = null)
        } else {
            val seen = markSeen(stored, movie, now)
            val checkIn = if (historic) null else CheckIn(stored, movie, now).also { checkInRepository.insert(it) }
            MovieListState.of(onWatchlist = false, seen = seen, checkedInAt = checkIn?.watchedAt)
        }
        recommendationService.refreshLater(stored)
        return state
    }

    /**
     * Checks the movie in on a [day] of the past week, today when null: a
     * watch, whose day shows on its tag and to friends. Further back is
     * history, which Mark as seen records, so a day outside the week is
     * refused. Today is the moment itself; an earlier day is its noon in UTC,
     * which falls on that same calendar day in practically every time zone.
     * A movie not seen yet is marked seen; one seen before gets one more
     * check-in, as a rewatch is, unless it has one on that day already.
     */
    suspend fun checkIn(user: User, movieId: String, day: LocalDate? = null, now: Instant = Instant.now()): MovieListState {
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        require(day == null || (!day.isAfter(today.plusDays(1)) && !day.isBefore(today.minus(CHECK_IN_WINDOW)))) {
            "A check-in is for a day of the past week; further back, mark the movie as watched."
        }
        val watchedAt = if (day == null || !day.isBefore(today)) now else noonOf(day)
        val movie = movieRepository.getById(movieId)
        val stored = userRepository.touch(user, now)
        val before = movieSeenRepository.findById(MovieSeenPk(userId = stored.id, movieId = movie.id))
        val seen = before?.let { if (watchedAt.isAfter(it.seenAt)) it.copy(seenAt = watchedAt).also { latest -> movieSeenRepository.update(latest) } else it }
            ?: markSeen(stored, movie, watchedAt)
        val checkIns = checkInRepository.findAll(stored, movie)
        val onThatDay = checkIns.any { dayOf(it.watchedAt) == dayOf(watchedAt) }
        if (!onThatDay) checkInRepository.insert(CheckIn(stored, movie, watchedAt))
        recommendationService.refreshLater(stored)
        val latest = (checkIns.map { it.watchedAt } + watchedAt).max()
        return MovieListState.of(onWatchlist = false, seen = seen, checkedInAt = latest)
    }

    /**
     * Gives a seen movie a thumbs up (true), a thumbs down (false), or takes
     * the vote back (null), and returns the state afterwards. A vote on a
     * movie not marked seen marks it seen first, without a check-in: a vote
     * says the movie was seen, not when. The vote is what sharpens the
     * recommendations most, so they are recomputed.
     */
    suspend fun vote(user: User, movieId: String, liked: Boolean?): MovieListState {
        val movie = movieRepository.getById(movieId)
        val now = Instant.now()
        val stored = userRepository.touch(user, now)
        val before = movieSeenRepository.findById(MovieSeenPk(userId = stored.id, movieId = movie.id))
        val seen = before?.copy(liked = liked)?.also { movieSeenRepository.update(it) }
            ?: markSeen(stored, movie, now, liked)
        recommendationService.refreshLater(stored)
        return MovieListState.of(onWatchlist = false, seen = seen, checkedInAt = checkInRepository.findAll(stored, movie).maxOfOrNull { it.watchedAt })
    }

    /** Marks a movie seen that was not: the row, the watchlist, and the event the recommendations count. */
    private fun markSeen(user: User, movie: Movie, at: Instant, liked: Boolean? = null): MovieSeen =
        MovieSeen(user = user, movie = movie, seenAt = at, liked = liked).also {
            movieSeenRepository.insert(it)
            watchlistRepository.remove(user, movie)
            recommendationService.record(user, movie, RecommendationEventKind.SEEN)
        }

    /**
     * Marks the titles of an export as seen ([ImdbExport], [TmdbExport]),
     * found first by the ids their sources give them ([MovieSourceService]);
     * a title no movie is known by counts as skipped. Every day the export
     * has for a title, the day it was rated or else added to the list, is a
     * check-in, so a title an export lists on several days has one for each.
     * A day matches a check-in made here within a week of it
     * ([matchCheckIns]), which then takes the export's day rather than being
     * counted twice; importing the same export again changes nothing. A title
     * without a day is seen without a check-in. The latest rating becomes a
     * thumb, 8 and up for a thumbs up and 5 and down for a thumbs down,
     * unless the user gave one here. As with marking one movie, the movies
     * come off the watchlist.
     *
     * The movies, the seen rows and the check-ins are read in one query each,
     * the seen rows written in one batched upsert, the check-ins in one
     * batched insert and one batched update, and the watchlist cleared in one
     * delete, in one transaction; then the recommendations are recomputed in
     * the background.
     */
    suspend fun importHistory(user: User, rows: List<ExportRow>, now: Instant = Instant.now()): HistoryImport {
        val movieIds = rows.groupBy { it.source }.mapValues { (source, sourceRows) ->
            // Only a signed-in user's import asks TMDB about the ids it does not know yet.
            movieSourceService.movieIds(source, sourceRows.mapTo(HashSet()) { it.externalId }, askTmdb = user.name != null)
        }
        val (placed, unplaced) = rows.partition { movieIds.getValue(it.source)[it.externalId] != null }
        val titles = placed.map { ImportedTitle(movieIds.getValue(it.source).getValue(it.externalId), it.date, it.rating) }
        val result = markImported(user, titles, now)
        return result.copy(skipped = result.skipped + unplaced.distinctBy { it.source to it.externalId }.size)
    }

    /** A title of an export once its movie is found. */
    private data class ImportedTitle(val movieId: String, val date: LocalDate?, val rating: Int?)

    private suspend fun markImported(user: User, titles: List<ImportedTitle>, now: Instant): HistoryImport = transaction {
        val stored = userRepository.touch(user, now)
        val rowsByTitle = titles.groupBy { it.movieId }
        val movies = movieRepository.findAllById(rowsByTitle.keys)
        val seenBefore = movieSeenRepository.findAll(stored).associateBy { it.movie.id }
        val checkInsBefore = checkInRepository.findAll(stored).groupBy { it.movie.id }
        val added = mutableListOf<CheckIn>()
        val moved = mutableListOf<CheckIn>()
        val seenRows = movies.map { movie ->
            val titleRows = rowsByTitle.getValue(movie.id)
            val match = matchCheckIns(checkInsBefore[movie.id].orEmpty(), titleRows.mapNotNull { it.date }.distinct().map(::noonOf))
            moved += match.moved
            added += match.added.map { CheckIn(stored, movie, it) }
            val seen = seenBefore[movie.id]
            val latestCheckIn = (checkInsBefore[movie.id].orEmpty().map { it.watchedAt } + match.moved.map { it.watchedAt } + match.added).maxOrNull()
            val latestRating = titleRows.sortedWith(compareBy(nullsFirst()) { it.date }).lastOrNull { it.rating != null }?.rating
            MovieSeen(
                user = stored,
                movie = movie,
                seenAt = latestCheckIn ?: seen?.seenAt ?: now,
                liked = seen?.liked ?: thumbFor(latestRating)
            )
        }
        movieSeenRepository.upsert(seenRows)
        checkInRepository.insert(added)
        checkInRepository.update(moved)
        watchlistRepository.removeAll(stored, movies)
        recommendationService.refreshLater(stored)
        HistoryImport(
            added = movies.count { it.id !in seenBefore },
            updated = movies.count { it.id in seenBefore },
            checkIns = added.size,
            skipped = rowsByTitle.size - movies.size
        )
    }

    /** How an import's days for one movie meet the check-ins made here: the ones that move to a day, and the days that are new. */
    data class CheckInMatch(val moved: List<CheckIn>, val added: List<Instant>)

    /**
     * Matches an import's days for one movie with its check-ins here: each
     * day takes the nearest check-in within a week that no other day took,
     * which moves to that day when it is not on it already, and a day with
     * none nearby is a new check-in. A check-in made here that no day takes
     * stays as it is.
     */
    fun matchCheckIns(existing: List<CheckIn>, days: List<Instant>): CheckInMatch {
        val free = existing.toMutableList()
        val moved = mutableListOf<CheckIn>()
        val added = mutableListOf<Instant>()
        for (day in days.sorted()) {
            val nearest = free.filter { Duration.between(it.watchedAt, day).abs() <= SAME_WATCH }
                .minByOrNull { Duration.between(it.watchedAt, day).abs() }
            if (nearest == null) {
                added += day
            } else {
                free -= nearest
                if (nearest.watchedAt != day) moved += nearest.copy(watchedAt = day)
            }
        }
        return CheckInMatch(moved, added)
    }

    /**
     * The movies the user has seen, by id, each with the day of its latest
     * check-in, or null for one seen without: the seen tag that every list
     * of movies puts on them shows the day, or the checkmark alone.
     */
    fun seenDates(user: User): Map<String, Instant?> {
        val latest = checkInRepository.findLatestPerMovie(user).associate { it.movie.entityId() to it.watchedAt }
        return movieSeenRepository.findSeenMovies(user).associate { it.entityId() to latest[it.entityId()] }
    }

    /** The seen page: how many movies the user has seen, and the first window, the latest marked or watched first. */
    suspend fun seenPage(user: User): SeenView = transaction(readOnly = true) {
        SeenView(count = movieSeenRepository.countFor(user), window = movieSeenRepository.scroll(user, MovieSeenRepository.newestFirst(PAGE_SIZE)))
    }

    /** The next window of the seen page: same cursor contract as the other scrolls. */
    suspend fun scrollSeen(user: User, cursor: String): Window<MovieSeen> = transaction(readOnly = true) {
        movieSeenRepository.scroll(user, MovieSeenRepository.newestFirst(PAGE_SIZE).from(cursor))
    }

    /** The check-ins page: the first window of the user's check-ins, the latest first. */
    suspend fun checkInsPage(user: User): CheckInsView = transaction(readOnly = true) {
        CheckInsView(checkInRepository.scroll(user, CheckInRepository.newestFirst(PAGE_SIZE)))
    }

    /** The next window of the check-ins page. */
    suspend fun scrollCheckIns(user: User, cursor: String): Window<CheckIn> = transaction(readOnly = true) {
        checkInRepository.scroll(user, CheckInRepository.newestFirst(PAGE_SIZE).from(cursor))
    }

    private fun dayOf(at: Instant): LocalDate = LocalDate.ofInstant(at, ZoneOffset.UTC)

    private fun noonOf(day: LocalDate): Instant = day.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC)

    companion object {
        private const val PAGE_SIZE = 36

        /** How far back a check-in can be dated: further back is history. */
        private val CHECK_IN_WINDOW: Period = Period.ofDays(7)

        /** How far apart an imported day and a check-in made here can be, and still be the same watch. */
        private val SAME_WATCH: Duration = Duration.ofDays(7)

        /** An IMDb rating as a thumb: up from 8, down to 5, none in between, where a movie was fine. */
        fun thumbFor(rating: Int?): Boolean? = when {
            rating == null -> null
            rating >= 8 -> true
            rating <= 5 -> false
            else -> null
        }
    }
}
