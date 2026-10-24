package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
import st.orm.demo.imdb.repository.RecommendationEventRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.service.ImdbExportRow
import st.orm.demo.imdb.service.ImdbImport
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.RecommendationService
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

    private fun service(orm: ORMTemplate) = MovieSeenService(
        orm.repository<MovieRepository>(),
        orm.repository<UserRepository>(),
        orm.repository<WatchlistRepository>(),
        orm.repository<MovieSeenRepository>(),
        RecommendationService(
            orm.repository<UserRepository>(),
            orm.repository<MovieRepository>(),
            orm.repository<MovieSeenRepository>(),
            orm.repository<WatchlistRepository>(),
            orm.repository<RecommendationDismissalRepository>(),
            orm.repository<MovieAdditionRepository>(),
            orm.repository<MovieGenreRepository>(),
            orm.repository<PrincipalRepository>(),
            orm.repository<RatingRepository>(),
            orm.repository<MovieViewRepository>(),
            orm.repository<RecommendationRepository>(),
            orm.repository<RecommendationEventRepository>()
        )
    )

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
            ImdbExportRow("tt0133093", LocalDate.of(2021, 3, 14), 4),
            ImdbExportRow("tt0234215", LocalDate.of(2021, 3, 15), 9),
            ImdbExportRow("tt0110912", LocalDate.of(2019, 1, 6), 7),
            ImdbExportRow("tt0903747", LocalDate.of(2020, 6, 1), 10) // a series: not in the catalogue
        )
        val result = capture.execute { runBlocking { service(orm).importFromImdb(user, rows) } }
        capture.printStatements("importFromImdb")

        assertEquals(ImdbImport(added = 2, updated = 1, skipped = 1), result)
        // The day comes from IMDb, also for the movie seen here already; its thumb stays.
        val matrix = seen.getById(MovieSeenPk(user.id, "tt0133093"))
        assertEquals(Instant.parse("2021-03-14T00:00:00Z"), matrix.seenAt)
        assertEquals(true, matrix.liked)
        // A 9 is a thumbs up, a 7 no thumb; Reloaded leaves the watchlist.
        assertEquals(true, seen.getById(MovieSeenPk(user.id, "tt0234215")).liked)
        assertNull(seen.getById(MovieSeenPk(user.id, "tt0110912")).liked)
        assertFalse(watchlist.contains(user, movies.getById("tt0234215")))
        // One batched upsert for the seen rows, one delete for the watchlist.
        assertEquals(1, capture.count(Operation.DELETE))
    }

    @Test
    fun `the IMDb rating maps to a thumb at the ends of the scale only`() {
        assertEquals(listOf(false, false, null, null, true, true), listOf(1, 5, 6, 7, 8, 10).map { MovieSeenService.thumbFor(it) })
        assertNull(MovieSeenService.thumbFor(null))
    }
}
