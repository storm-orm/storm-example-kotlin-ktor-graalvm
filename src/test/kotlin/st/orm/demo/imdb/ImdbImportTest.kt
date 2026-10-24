package st.orm.demo.imdb

import st.orm.demo.imdb.repository.CheckInRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.model.Source
import st.orm.demo.imdb.service.ExportRow
import st.orm.demo.imdb.service.HistoryImport
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant
import java.time.LocalDate

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class ImdbImportTest {

    private fun service(orm: ORMTemplate) = movieSeenService(orm)

    @Test
    fun `an import marks the movies seen on IMDb's day, keeps the thumbs given here, and skips what the catalogue lacks`(orm: ORMTemplate, capture: SqlCapture) {
        val movies = orm.repository<MovieRepository>()
        val seen = orm.repository<MovieSeenRepository>()
        val watchlist = orm.repository<WatchlistRepository>()
        val user = orm.insertUser("visitor-import")
        // Already here: The Matrix, seen and liked yesterday; Reloaded on the watchlist.
        seen.insert(MovieSeen(user, movies.getById("tt0133093"), Instant.parse("2026-10-23T20:00:00Z"), liked = true))
        watchlist.insert(Watchlist(user, movies.getById("tt0234215"), Instant.parse("2026-10-01T10:00:00Z")))

        val rows = listOf(
            ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2021, 3, 14), 4),
            ExportRow(Source.IMDB, "tt0234215", LocalDate.of(2021, 3, 15), 9),
            ExportRow(Source.IMDB, "tt0110912", LocalDate.of(2019, 1, 6), 7),
            ExportRow(Source.IMDB, "tt0903747", LocalDate.of(2020, 6, 1), 10) // a series: not in the catalogue
        )
        val result = capture.execute { runBlocking { service(orm).importHistory(user, rows) } }
        capture.printStatements("importHistory")

        assertEquals(HistoryImport(added = 2, updated = 1, checkIns = 3, skipped = 1), result)
        // Each day is a check-in, at noon UTC, also for the movie seen here already; its thumb stays.
        val matrix = seen.getById(MovieSeenPk(user.id, "tt0133093"))
        assertEquals(Instant.parse("2021-03-14T12:00:00Z"), matrix.seenAt)
        assertEquals(true, matrix.liked)
        // A 9 is a thumbs up, a 7 no thumb; Reloaded leaves the watchlist.
        assertEquals(true, seen.getById(MovieSeenPk(user.id, "tt0234215")).liked)
        assertNull(seen.getById(MovieSeenPk(user.id, "tt0110912")).liked)
        assertFalse(watchlist.contains(user, movies.getById("tt0234215")))
        // One batched upsert for the seen rows, one delete for the watchlist.
        assertEquals(1, capture.count(Operation.DELETE))
    }

    @Test
    fun `a title listed on several days is a check-in on each, the latest rating given its thumb`(orm: ORMTemplate) {
        val user = orm.insertUser("visitor-check-ins")
        val rows = listOf(
            ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2019, 5, 1), 9),
            ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2023, 8, 12), null),
            ExportRow(Source.IMDB, "tt0133093", null, 4),
            ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2021, 1, 3), 6)
        )

        val result = runBlocking { service(orm).importHistory(user, rows) }

        assertEquals(HistoryImport(added = 1, updated = 0, checkIns = 3, skipped = 0), result)
        val checkIns = orm.repository<CheckInRepository>().findAll(user).map { it.watchedAt }.sorted()
        assertEquals(listOf("2019-05-01", "2021-01-03", "2023-08-12").map { Instant.parse(it + "T12:00:00Z") }, checkIns)
        val matrix = orm.repository<MovieSeenRepository>().getById(MovieSeenPk(user.id, "tt0133093"))
        assertEquals(Instant.parse("2023-08-12T12:00:00Z"), matrix.seenAt)
        // The latest day has no rating; the latest rating among the dated ones, a 6, gives no thumb.
        assertNull(matrix.liked)

        // The same export again changes nothing.
        assertEquals(0, runBlocking { service(orm).importHistory(user, rows) }.checkIns)
        assertEquals(3, orm.repository<CheckInRepository>().findAll(user).size)
    }

    @Test
    fun `a check-in made here takes the day an import has within a week of it, rather than counting twice`(orm: ORMTemplate) {
        val user = orm.insertUser("visitor-matching")
        val service = service(orm)
        // Checked in here on the 20th; IMDb says the 17th, and also has an older watch.
        runBlocking { service.checkIn(user, "tt0133093", LocalDate.of(2026, 10, 20), Instant.parse("2026-10-24T20:00:00Z")) }
        val rows = listOf(ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2026, 10, 17), 9), ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2024, 2, 2), 8))

        val result = runBlocking { service.importHistory(user, rows) }

        assertEquals(1, result.checkIns)
        assertEquals(
            listOf(Instant.parse("2024-02-02T12:00:00Z"), Instant.parse("2026-10-17T12:00:00Z")),
            orm.repository<CheckInRepository>().findAll(user).map { it.watchedAt }.sorted()
        )
    }

    @Test
    fun `a title with a day is a check-in, one without is history, and either way the seen dates say so`(orm: ORMTemplate) {
        val user = orm.insertUser("visitor-history")
        val service = service(orm)
        runBlocking { service.importHistory(user, listOf(ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2021, 3, 14), null), ExportRow(Source.IMDB, "tt0111161", null, null))) }
        // Marked from the catch-up page, as seen before: history too.
        runBlocking { service.toggle(user, "tt0110912", historic = true) }
        // Marked as seen today: a check-in.
        runBlocking { service.toggle(user, "tt0068646") }

        val dates = service.seenDates(user)
        assertEquals(Instant.parse("2021-03-14T12:00:00Z"), dates["tt0133093"])
        assertEquals(setOf("tt0111161", "tt0110912"), dates.filterValues { it == null }.keys)
        assertTrue(dates["tt0068646"] != null)
    }

    @Test
    fun `a check-in takes a day of the past week, and turns history into a check-in`(orm: ORMTemplate) {
        val user = orm.insertUser("visitor-check-in")
        val service = service(orm)
        val now = Instant.parse("2026-10-24T20:00:00Z")
        runBlocking { service.toggle(user, "tt0133093", historic = true) }

        // Yesterday's check-in: noon UTC that day, a check-in no longer history.
        val state = runBlocking { service.checkIn(user, "tt0133093", LocalDate.of(2026, 10, 23), now) }
        assertEquals(Instant.parse("2026-10-23T12:00:00Z"), state.seenAt)
        // Today's is the moment itself.
        assertEquals(now, runBlocking { service.checkIn(user, "tt0111161", LocalDate.of(2026, 10, 24), now) }.seenAt)
        // Further back than a week is history: refused.
        assertThrows<IllegalArgumentException> { runBlocking { service.checkIn(user, "tt0110912", LocalDate.of(2026, 10, 10), now) } }
    }

    @Test
    fun `the IMDb rating maps to a thumb at the ends of the scale only`() {
        assertEquals(listOf(false, false, null, null, true, true), listOf(1, 5, 6, 7, 8, 10).map { MovieSeenService.thumbFor(it) })
        assertNull(MovieSeenService.thumbFor(null))
    }
}
