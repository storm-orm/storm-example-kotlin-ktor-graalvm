package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.Scrollable
import st.orm.demo.imdb.model.MovieSummary_
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieSummaryRepositoryTest {

    @Test
    fun `searchByTitle selects only the projected columns`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        val window = capture.execute {
            movieSummaryRepository.searchByTitle("matrix", Scrollable.of(MovieSummary_.id, 10))
        }
        capture.printStatements("searchByTitle")
        assertEquals(1, capture.count(Operation.SELECT))
        val statement = capture.statements().single().statement()
        // The projection narrows the SELECT: no columns beyond id, title, year.
        assertFalse(statement.contains("original_title"))
        assertFalse(statement.contains("runtime_minutes"))
        assertEquals(
            listOf("The Matrix", "The Matrix Reloaded"),
            window.content().map { it.primaryTitle }.sorted()
        )
    }

    @Test
    fun `searchByTitle scrolls through windows with a cursor`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()

        val firstWindow = movieSummaryRepository.searchByTitle("matrix", Scrollable.of(MovieSummary_.id, 1))
        assertEquals(1, firstWindow.content().size)
        assertTrue(firstWindow.hasNext())
        val cursor = firstWindow.nextCursor()
        assertNotNull(cursor)

        val secondWindow = capture.execute {
            movieSummaryRepository.searchByTitle("matrix", Scrollable.fromCursor(MovieSummary_.id, cursor!!))
        }
        capture.printStatements("searchByTitle-cursor")
        assertEquals(1, secondWindow.content().size)
        assertFalse(firstWindow.content().first().id == secondWindow.content().first().id)
    }

    @Test
    fun `scrolling server-side uses window next() without any cursor`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()

        val firstWindow = movieSummaryRepository.searchByTitle("matrix", Scrollable.of(MovieSummary_.id, 1))
        assertTrue(firstWindow.hasNext())

        // Server-side navigation never touches the cursor string: next() is
        // a ready-to-use typed Scrollable. The cursor is merely its
        // serialized form for crossing the client-server boundary.
        val secondWindow = capture.execute {
            movieSummaryRepository.searchByTitle("matrix", firstWindow.next())
        }
        capture.printStatements("searchByTitle-next")
        assertEquals(1, secondWindow.content().size)
        assertFalse(firstWindow.content().first().id == secondWindow.content().first().id)
    }

    @Test
    fun `findTitleSuggestions ranks by vote count`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        val suggestions = capture.execute {
            movieSummaryRepository.findTitleSuggestions("matrix", 5)
        }
        capture.printStatements("findTitleSuggestions")
        assertEquals(1, capture.count(Operation.SELECT))
        assertTrue(capture.statements().single().statement().contains("rating"))
        // The Matrix (2M votes) must rank above The Matrix Reloaded (600k).
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), suggestions.map { it.primaryTitle })
    }

    @Test
    fun `scrollByGenre joins the junction table and scrolls on the movie key`(orm: ORMTemplate, capture: SqlCapture) {
        val genreRepository = orm.repository<GenreRepository>()
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        val drama = genreRepository.findByName("Drama")!!

        capture.clear()
        val firstWindow = capture.execute {
            movieSummaryRepository.scrollByGenre(drama, Scrollable.of(MovieSummary_.id, 2))
        }
        capture.printStatements("scrollByGenre")
        assertEquals(1, capture.count(Operation.SELECT))
        assertTrue(capture.statements().single().statement().contains("movie_genre"))
        assertEquals(2, firstWindow.content().size)
        assertTrue(firstWindow.hasNext())

        val secondWindow = movieSummaryRepository.scrollByGenre(
            drama,
            Scrollable.fromCursor(MovieSummary_.id, firstWindow.nextCursor()!!)
        )
        // Three drama movies in total: 2 in the first window, 1 in the second.
        assertEquals(1, secondWindow.content().size)
    }
}
