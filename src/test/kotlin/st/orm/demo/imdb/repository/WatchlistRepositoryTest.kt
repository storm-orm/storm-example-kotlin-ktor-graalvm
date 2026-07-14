package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class WatchlistRepositoryTest {

    @Test
    fun `the toggle cycle exists-insert-exists-remove works on the movie key`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        // Pulp Fiction is not touched by other tests in this class — the
        // @StormTest database is shared across the class's test methods.
        val pulpFiction = movieRepository.getById("tt0110912")

        capture.run {
            assertFalse(watchlistRepository.existsById(pulpFiction))

            watchlistRepository.insert(Watchlist(movie = pulpFiction, addedAt = Instant.now()))
            assertTrue(watchlistRepository.existsById(pulpFiction))

            watchlistRepository.removeById(pulpFiction)
            assertFalse(watchlistRepository.existsById(pulpFiction))
        }
        capture.printStatements("watchlistToggle")
        assertEquals(1, capture.count(Operation.INSERT))
        assertEquals(1, capture.count(Operation.DELETE))
    }

    @Test
    fun `findPage paginates newest first`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val baseInstant = Instant.parse("2026-07-01T10:00:00Z")
        listOf("tt0133093", "tt0111161", "tt0068646").forEachIndexed { index, movieId ->
            watchlistRepository.insert(
                Watchlist(movie = movieRepository.getById(movieId), addedAt = baseInstant.plusSeconds(index.toLong()))
            )
        }

        capture.clear()
        val page = capture.execute { watchlistRepository.findPage(pageNumber = 0, pageSize = 2) }
        capture.printStatements("findPage")
        assertEquals(2, page.content().size)
        assertEquals(3, page.totalCount())
        assertEquals(2, page.totalPages())
        assertTrue(page.hasNext())
        // Newest first: The Godfather was added last.
        assertEquals("The Godfather", page.content().first().movie.primaryTitle)
    }
}
