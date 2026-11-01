package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.Scrollable
import st.orm.demo.imdb.model.Movie_
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieRepositoryTest {

    @Test
    fun `countMoviesPerDecade groups by the computed decade`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val decades = capture.execute {
            movieRepository.countMoviesPerDecade()
        }
        capture.printStatements("countMoviesPerDecade")
        assertTrue(capture.statements().single().statement().contains("GROUP BY"))
        assertEquals(listOf(1970 to 1L, 1990 to 3L, 2000 to 2L), decades.map { it.decade to it.movieCount })
    }

    @Test
    fun `searchMovies reads the matching movies in one statement`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val window = capture.execute {
            movieRepository.searchMovies("matrix", Scrollable.of(Movie_.id, 10))
        }
        capture.printStatements("searchMovies")
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals(
            listOf("The Matrix", "The Matrix Reloaded"),
            window.content().map { it.primaryTitle }.sorted()
        )
    }

    @Test
    fun `searchMovies matches every word of the query in any order`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        fun titles(query: String) =
            movieRepository.searchMovies(query, Scrollable.of(Movie_.id, 10))
                .content().map { it.primaryTitle }.sorted()
        assertEquals(listOf("The Matrix Reloaded"), titles("reloaded MATRIX"))
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), titles("\"the matrix\""))
        assertEquals(emptyList<String>(), titles("matrix godfather"))
        // % and _ match themselves rather than acting as wildcards.
        assertEquals(emptyList<String>(), titles("%"))
    }

    @Test
    fun `searchMovies matches the original title`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        fun titles(query: String) =
            movieRepository.searchMovies(query, Scrollable.of(Movie_.id, 10))
                .content().map { it.primaryTitle }
        assertEquals(listOf("Amélie"), titles("fabuleux destin"))
        assertEquals(listOf("Amélie"), titles("amélie poulain"))
        assertEquals(listOf("Amélie"), titles("amelie poulain"))
        assertEquals(listOf("Amélie"), titles("AMELIE"))
        assertEquals(emptyList<String>(), titles("fabuleux matrix"))
    }

    @Test
    fun `searchMovies matches the names of the people credited`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        fun titles(query: String) =
            movieRepository.searchMovies(query, Scrollable.of(Movie_.id, 10))
                .content().map { it.primaryTitle }.sorted()
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), titles("keanu"))
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), titles("matrix keanu"))
        assertEquals(listOf("The Matrix Reloaded"), titles("reloaded fishburne"))
        assertEquals(listOf("The Shawshank Redemption"), titles("freeman"))
        assertEquals(emptyList<String>(), titles("keanu godfather"))
    }

    @Test
    fun `searchMovies scrolls through windows with a cursor`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()

        val firstWindow = movieRepository.searchMovies("matrix", Scrollable.of(Movie_.id, 1))
        assertEquals(1, firstWindow.content().size)
        assertTrue(firstWindow.hasNext())
        val cursor = firstWindow.nextCursor()
        assertNotNull(cursor)

        val secondWindow = capture.execute {
            movieRepository.searchMovies("matrix", Scrollable.of(Movie_.id, 1).from(cursor!!))
        }
        capture.printStatements("searchMovies-cursor")
        assertEquals(1, secondWindow.content().size)
        assertFalse(firstWindow.content().first().id == secondWindow.content().first().id)
    }

    @Test
    fun `scrolling server-side uses window next() without any cursor`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()

        val firstWindow = movieRepository.searchMovies("matrix", Scrollable.of(Movie_.id, 1))
        assertTrue(firstWindow.hasNext())

        // Server-side navigation never touches the cursor string: next() is
        // a ready-to-use typed Scrollable, ordering and size included. The
        // cursor carries the position alone, for crossing the client-server
        // boundary where the request states the rest.
        val secondWindow = capture.execute {
            movieRepository.searchMovies("matrix", firstWindow.next()!!)
        }
        capture.printStatements("searchMovies-next")
        assertEquals(1, secondWindow.content().size)
        assertFalse(firstWindow.content().first().id == secondWindow.content().first().id)
    }

    @Test
    fun `findMovieSuggestions ranks by vote count`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val suggestions = capture.execute {
            movieRepository.findMovieSuggestions("matrix", 5)
        }
        capture.printStatements("findMovieSuggestions")
        assertEquals(1, capture.count(Operation.SELECT))
        assertTrue(capture.statements().single().statement().contains("rating"))
        // The Matrix (2M votes) must rank above The Matrix Reloaded (600k).
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), suggestions.map { it.primaryTitle })
    }

    @Test
    fun `findMovieSuggestions ignores the noise words of titles`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        fun titles(query: String) =
            movieRepository.findMovieSuggestions(query, 5).map { it.primaryTitle }
        // "the" is in five of the six titles; alone it suggests nothing.
        assertEquals(emptyList<String>(), titles("the"))
        assertEquals(emptyList<String>(), titles("THE"))
        // Next to another word it takes no part: "the god" suggests what "god" does.
        assertEquals(listOf("The Godfather"), titles("god"))
        assertEquals(listOf("The Godfather"), titles("the god"))
    }

    @Test
    fun `findMovieSuggestions matches a two-letter word at the start of a word`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        fun titles(query: String) =
            movieRepository.findMovieSuggestions(query, 5).map { it.primaryTitle }
        assertEquals(listOf("The Godfather"), titles("go"))
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), titles("ma"))
        // Inside a word it does not match: "at" is in Matrix, but starts no word.
        assertEquals(emptyList<String>(), titles("at"))
        // A word of one letter takes no part, so it suggests nothing.
        assertEquals(emptyList<String>(), titles("m"))
        assertEquals(emptyList<String>(), titles("  "))
    }

    @Test
    fun `findMovieSuggestions ignores accents`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        fun titles(query: String) =
            movieRepository.findMovieSuggestions(query, 5).map { it.primaryTitle }
        assertEquals(listOf("Amélie"), titles("amelie"))
        assertEquals(listOf("Amélie"), titles("AMÉLIE"))
        assertEquals(listOf("Amélie"), titles("fabuleux"))
    }

    @Test
    fun `scrollByGenre scrolls a genre most voted first`(orm: ORMTemplate, capture: SqlCapture) {
        val genreRepository = orm.repository<GenreRepository>()
        val movieRepository = orm.repository<MovieRepository>()
        val drama = genreRepository.findByName("Drama")!!

        capture.clear()
        val firstWindow = capture.execute {
            movieRepository.scrollByGenre(drama, MovieRepository.mostVotedFirst(2))
        }
        capture.printStatements("scrollByGenre")
        assertEquals(1, capture.count(Operation.SELECT))
        assertTrue(capture.statements().single().statement().contains("movie_genre"))
        // Shawshank (2.9M votes) and Pulp Fiction (2.2M) before The Godfather
        // (2.0M), the reverse of their ids.
        assertEquals(listOf("The Shawshank Redemption", "Pulp Fiction"), firstWindow.content().map { it.primaryTitle })
        assertTrue(firstWindow.hasNext())

        val secondWindow = movieRepository.scrollByGenre(
            drama,
            MovieRepository.mostVotedFirst(2).from(firstWindow.nextCursor()!!)
        )
        assertEquals(listOf("The Godfather"), secondWindow.content().map { it.primaryTitle })
    }
}
