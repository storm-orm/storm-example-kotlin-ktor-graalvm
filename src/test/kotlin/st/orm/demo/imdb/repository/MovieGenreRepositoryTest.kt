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
import java.math.BigDecimal

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieGenreRepositoryTest {

    @Test
    fun `findGenres returns the genres of one movie`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val movieGenreRepository = orm.repository<MovieGenreRepository>()
        val matrix = movieRepository.getById("tt0133093")
        capture.clear()
        val genres = capture.execute {
            movieGenreRepository.findGenres(matrix)
        }
        capture.printStatements("findGenres")
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals(listOf("Action", "Sci-Fi"), genres.map { it.name })
    }

    @Test
    fun `findGenreRatingStatistics filters groups with HAVING`(orm: ORMTemplate, capture: SqlCapture) {
        val movieGenreRepository = orm.repository<MovieGenreRepository>()
        val statistics = capture.execute {
            movieGenreRepository.findGenreRatingStatistics(minimumMovieCount = 3, limit = 10)
        }
        capture.printStatements("findGenreRatingStatistics")
        assertTrue(capture.statements().single().statement().contains("HAVING"))
        // Only Drama has 3+ rated movies; average of 9.3, 8.9, 9.2 is ~9.13.
        val drama = statistics.single()
        assertEquals("Drama", drama.genre.name)
        assertEquals(3L, drama.movieCount)
        assertTrue(drama.averageRating > BigDecimal("9.0") && drama.averageRating < BigDecimal("9.2"))
    }
}
