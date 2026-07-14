package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class RatingRepositoryTest {

    @Test
    fun `findTopRated returns movies with their full graph in one query`(orm: ORMTemplate, capture: SqlCapture) {
        val ratingRepository = orm.repository<RatingRepository>()
        val topRated = capture.execute {
            ratingRepository.findTopRated(minimumVoteCount = 1000, limit = 3)
        }
        capture.printStatements("findTopRated")
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals(
            listOf("The Shawshank Redemption", "The Godfather", "Pulp Fiction"),
            topRated.map { it.movie.primaryTitle }
        )
    }

    @Test
    fun `findTopMovies without genre sorts all movies by rating`(orm: ORMTemplate, capture: SqlCapture) {
        val ratingRepository = orm.repository<RatingRepository>()
        val topMovies = capture.execute {
            ratingRepository.findTopMovies(genre = null, sortBy = TopMoviesSort.RATING, minimumVoteCount = 1000, limit = 10)
        }
        capture.printStatements("findTopMovies-all")
        // No genre filter: the junction table must not be joined.
        assertTrue(!capture.statements().single().statement().contains("movie_genre"))
        assertEquals(5, topMovies.size)
        assertEquals("The Shawshank Redemption", topMovies.first().movie.primaryTitle)
    }

    @Test
    fun `findTopMovies with genre joins the junction table`(orm: ORMTemplate, capture: SqlCapture) {
        val genreRepository = orm.repository<GenreRepository>()
        val ratingRepository = orm.repository<RatingRepository>()
        val action = genreRepository.findByName("Action")!!
        capture.clear()

        val topActionMovies = capture.execute {
            ratingRepository.findTopMovies(genre = action, sortBy = TopMoviesSort.RATING, minimumVoteCount = 1000, limit = 10)
        }
        capture.printStatements("findTopMovies-action")
        assertTrue(capture.statements().single().statement().contains("movie_genre"))
        assertEquals(
            listOf("The Matrix", "The Matrix Reloaded"),
            topActionMovies.map { it.movie.primaryTitle }
        )
    }

    @Test
    fun `findTopMovies sorted by year puts newest first`(orm: ORMTemplate, capture: SqlCapture) {
        val ratingRepository = orm.repository<RatingRepository>()
        val newestMovies = capture.execute {
            ratingRepository.findTopMovies(genre = null, sortBy = TopMoviesSort.YEAR, minimumVoteCount = 1000, limit = 10)
        }
        capture.printStatements("findTopMovies-year")
        assertEquals("The Matrix Reloaded", newestMovies.first().movie.primaryTitle)
    }
}
