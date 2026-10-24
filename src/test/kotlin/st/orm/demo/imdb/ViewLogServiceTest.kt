package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieViewDayRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.service.ViewLogProperties
import st.orm.demo.imdb.service.ViewLogService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant
import java.time.LocalDate
import java.time.Period

/**
 * The nightly fold: the views older than the kept period become one row
 * per movie and day and leave the log; the recent ones stay. The fixture
 * holds three views on 1 July 2026, two of The Matrix and one of Shawshank.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class ViewLogServiceTest {

    private val now = Instant.parse("2026-10-23T12:00:00Z")

    @Test
    fun `the cut is midnight the kept period ago`(orm: ORMTemplate) {
        val service = ViewLogService(orm.repository<MovieViewRepository>(), orm.repository<MovieViewDayRepository>(), ViewLogProperties(keep = Period.ofDays(30)))

        assertEquals(Instant.parse("2026-09-23T00:00:00Z"), service.cutoff(now))
    }

    @Test
    fun `old views fold into day counts and leave the log, recent views stay`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val movieViewRepository = orm.repository<MovieViewRepository>()
        val movieViewDayRepository = orm.repository<MovieViewDayRepository>()
        val service = ViewLogService(movieViewRepository, movieViewDayRepository, ViewLogProperties(keep = Period.ofDays(30)))
        // A recent view, which the fold must leave alone.
        movieViewRepository.insert(MovieView(movieRepository.getById("tt0068646"), now.minusSeconds(3600)))

        val folded = capture.execute { runBlocking { service.foldOldViews(now) } }

        capture.printStatements("foldOldViews")
        assertEquals(2, folded)
        assertEquals(1, capture.count(Operation.DELETE))
        val days = movieViewDayRepository.findSince(LocalDate.of(2026, 7, 1))
        assertEquals(listOf("tt0111161" to 1, "tt0133093" to 2), days.map { it.movie.id() to it.viewCount })
        assertTrue(days.all { it.viewDate == LocalDate.of(2026, 7, 1) })
        assertEquals(listOf("tt0068646"), movieViewRepository.findAll().map { it.movie.id() })

        // A second run has nothing left to fold.
        assertEquals(0, runBlocking { service.foldOldViews(now) })
    }
}
