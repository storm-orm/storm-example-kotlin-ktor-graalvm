package st.orm.demo.imdb.service

import org.slf4j.LoggerFactory
import st.orm.demo.imdb.model.MovieViewDay
import st.orm.demo.imdb.repository.MovieViewDayRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.template.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Keeps the view log short. Every visit to a movie page is a row of
 * movie_view, which the home page reads for the last fifty and the
 * recommendations for the week's most viewed; the rest is history. The
 * nightly fold turns the views older than [ViewLogProperties.keep] into
 * one row per movie and day and removes them, in one transaction, so the
 * log holds a month and its indexes stay small, and the counts remain.
 *
 * The cut is a midnight, so a day is folded whole: its row is written by
 * key, the movie and the date, when none of its views remain. The write is
 * an upsert, so a run that is interrupted and repeated writes the same rows.
 */
class ViewLogService(
    private val movieViewRepository: MovieViewRepository,
    private val movieViewDayRepository: MovieViewDayRepository,
    private val properties: ViewLogProperties
) {
    private val logger = LoggerFactory.getLogger(ViewLogService::class.java)

    /** Folds the views older than the kept period into day counts and removes them. Returns the number of day rows written. */
    suspend fun foldOldViews(now: Instant = Instant.now()): Int = transaction {
        val cutoff = cutoff(now)
        val days = movieViewRepository.countPerMovieAndDayBefore(cutoff)
        if (days.isEmpty()) return@transaction 0
        movieViewDayRepository.upsert(days.map { MovieViewDay(movie = it.movie, viewDate = it.day, viewCount = it.viewCount.toInt()) })
        val removed = movieViewRepository.removeBefore(cutoff)
        logger.info("Folded {} views before {} into {} day rows.", removed, cutoff, days.size)
        days.size
    }

    /** The first moment that stays a view of its own: midnight UTC, the kept period ago. */
    fun cutoff(now: Instant): Instant =
        LocalDate.ofInstant(now, ZoneOffset.UTC).minus(properties.keep).atStartOfDay(ZoneOffset.UTC).toInstant()
}
