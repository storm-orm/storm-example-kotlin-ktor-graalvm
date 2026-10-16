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
    fun `searchMovies selects only the projected columns`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        val window = capture.execute {
            movieSummaryRepository.searchMovies("matrix", Scrollable.of(MovieSummary_.id, 10))
        }
        capture.printStatements("searchMovies")
        assertEquals(1, capture.count(Operation.SELECT))
        val statement = capture.statements().single().statement()
        // The projection narrows the SELECT: no columns beyond id, titles, year.
        assertFalse(statement.contains("runtime_minutes"))
        assertEquals(
            listOf("The Matrix", "The Matrix Reloaded"),
            window.content().map { it.primaryTitle }.sorted()
        )
    }

    @Test
    fun `searchMovies matches every word of the query in any order`(orm: ORMTemplate) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        fun titles(query: String) =
            movieSummaryRepository.searchMovies(query, Scrollable.of(MovieSummary_.id, 10))
                .content().map { it.primaryTitle }.sorted()
        assertEquals(listOf("The Matrix Reloaded"), titles("reloaded MATRIX"))
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), titles("\"the matrix\""))
        assertEquals(emptyList<String>(), titles("matrix godfather"))
        // % and _ match themselves rather than acting as wildcards.
        assertEquals(emptyList<String>(), titles("%"))
    }

    @Test
    fun `searchMovies matches the original title`(orm: ORMTemplate) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        fun titles(query: String) =
            movieSummaryRepository.searchMovies(query, Scrollable.of(MovieSummary_.id, 10))
                .content().map { it.primaryTitle }
        assertEquals(listOf("Amélie"), titles("fabuleux destin"))
        assertEquals(listOf("Amélie"), titles("amélie poulain"))
        assertEquals(emptyList<String>(), titles("fabuleux matrix"))
    }

    @Test
    fun `searchMovies matches the names of the people credited`(orm: ORMTemplate) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        fun titles(query: String) =
            movieSummaryRepository.searchMovies(query, Scrollable.of(MovieSummary_.id, 10))
                .content().map { it.primaryTitle }.sorted()
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), titles("keanu"))
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), titles("matrix keanu"))
        assertEquals(listOf("The Matrix Reloaded"), titles("reloaded fishburne"))
        assertEquals(listOf("The Shawshank Redemption"), titles("freeman"))
        assertEquals(emptyList<String>(), titles("keanu godfather"))
    }

    @Test
    fun `searchMovies scrolls through windows with a cursor`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()

        val firstWindow = movieSummaryRepository.searchMovies("matrix", Scrollable.of(MovieSummary_.id, 1))
        assertEquals(1, firstWindow.content().size)
        assertTrue(firstWindow.hasNext())
        val cursor = firstWindow.nextCursor()
        assertNotNull(cursor)

        val secondWindow = capture.execute {
            movieSummaryRepository.searchMovies("matrix", Scrollable.of(MovieSummary_.id, 1).from(cursor!!))
        }
        capture.printStatements("searchMovies-cursor")
        assertEquals(1, secondWindow.content().size)
        assertFalse(firstWindow.content().first().id == secondWindow.content().first().id)
    }

    @Test
    fun `scrolling server-side uses window next() without any cursor`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()

        val firstWindow = movieSummaryRepository.searchMovies("matrix", Scrollable.of(MovieSummary_.id, 1))
        assertTrue(firstWindow.hasNext())

        // Server-side navigation never touches the cursor string: next() is
        // a ready-to-use typed Scrollable, ordering and size included. The
        // cursor carries the position alone, for crossing the client-server
        // boundary where the request states the rest.
        val secondWindow = capture.execute {
            movieSummaryRepository.searchMovies("matrix", firstWindow.next()!!)
        }
        capture.printStatements("searchMovies-next")
        assertEquals(1, secondWindow.content().size)
        assertFalse(firstWindow.content().first().id == secondWindow.content().first().id)
    }

    @Test
    fun `findMovieSuggestions ranks by vote count`(orm: ORMTemplate, capture: SqlCapture) {
        val movieSummaryRepository = orm.repository<MovieSummaryRepository>()
        val suggestions = capture.execute {
            movieSummaryRepository.findMovieSuggestions("matrix", 5)
        }
        capture.printStatements("findMovieSuggestions")
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
            Scrollable.of(MovieSummary_.id, 2).from(firstWindow.nextCursor()!!)
        )
        // Three drama movies in total: 2 in the first window, 1 in the second.
        assertEquals(1, secondWindow.content().size)
    }
}
