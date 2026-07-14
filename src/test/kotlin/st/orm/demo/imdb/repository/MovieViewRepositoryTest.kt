package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.refById
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieViewRepositoryTest {

    @Test
    fun `findRecentViews stays on the view table thanks to Ref`(orm: ORMTemplate, capture: SqlCapture) {
        val movieViewRepository = orm.repository<MovieViewRepository>()
        val recentViews = capture.execute {
            movieViewRepository.findRecentViews(10)
        }
        capture.printStatements("findRecentViews")
        assertEquals(1, capture.count(Operation.SELECT))
        // The movie field is a Ref, so no join on the movie table is needed.
        assertFalse(capture.statements().single().statement().lowercase().contains("join"))
        assertTrue(recentViews.size >= 3)
        assertEquals("tt0133093", recentViews.first().movie.id())
    }

    @Test
    fun `recording a view inserts by id without loading the movie`(orm: ORMTemplate, capture: SqlCapture) {
        val movieViewRepository = orm.repository<MovieViewRepository>()
        capture.execute {
            movieViewRepository.insert(
                // Older than the seeded views so it never becomes the newest.
                MovieView(movie = refById<Movie>("tt0110912"), viewedAt = Instant.parse("2026-06-30T00:00:00Z"))
            )
        }
        capture.printStatements("recordView")
        // A single INSERT: no SELECT is needed to record the view.
        assertEquals(1, capture.count(Operation.INSERT))
        assertEquals(0, capture.count(Operation.SELECT))
    }
}
