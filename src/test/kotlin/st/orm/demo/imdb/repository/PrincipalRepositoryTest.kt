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
class PrincipalRepositoryTest {

    @Test
    fun `findCast returns the full graph in billing order with one query`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val principalRepository = orm.repository<PrincipalRepository>()
        val matrix = movieRepository.getById("tt0133093")
        capture.clear()
        val cast = capture.execute {
            principalRepository.findCast(matrix)
        }
        capture.printStatements("findCast")
        // One query loads the credits including their movie and person graph.
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne"), cast.map { it.person.primaryName })
        assertEquals(listOf("Neo", "Morpheus"), cast.map { it.characters })
        assertEquals("The Matrix", cast.first().movie.primaryTitle)
    }

    @Test
    fun `findFilmography sorts by rating descending`(orm: ORMTemplate, capture: SqlCapture) {
        val personRepository = orm.repository<PersonRepository>()
        val principalRepository = orm.repository<PrincipalRepository>()
        val keanuReeves = personRepository.getById("nm0000206")
        capture.clear()
        val filmography = capture.execute {
            principalRepository.findFilmography(keanuReeves)
        }
        capture.printStatements("findFilmography")
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals(
            listOf("The Matrix" to BigDecimal("8.7"), "The Matrix Reloaded" to BigDecimal("7.2")),
            filmography.map { it.principal.movie.primaryTitle to it.averageRating }
        )
    }

    @Test
    fun `findStatistics aggregates movie count and average rating`(orm: ORMTemplate, capture: SqlCapture) {
        val personRepository = orm.repository<PersonRepository>()
        val principalRepository = orm.repository<PrincipalRepository>()
        val keanuReeves = personRepository.getById("nm0000206")
        capture.clear()
        val statistics = capture.execute {
            principalRepository.findStatistics(keanuReeves)
        }
        capture.printStatements("findStatistics")
        assertEquals(2L, statistics.movieCount)
        // Average of 8.7 and 7.2 is 7.95.
        assertEquals(0, BigDecimal("7.95").compareTo(statistics.averageRating))
    }

    @Test
    fun `findMoviesSharingCast ranks by shared cast members`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val personRepository = orm.repository<PersonRepository>()
        val principalRepository = orm.repository<PrincipalRepository>()
        val matrix = movieRepository.getById("tt0133093")
        val castMembers = personRepository.findAllById(listOf("nm0000206", "nm0000401"))
        capture.clear()
        val relatedMovies = capture.execute {
            principalRepository.findMoviesSharingCast(castMembers, excludedMovie = matrix, limit = 6)
        }
        capture.printStatements("findMoviesSharingCast")
        assertTrue(capture.statements().single().statement().contains("GROUP BY"))
        // The Matrix Reloaded shares both cast members with The Matrix.
        val related = relatedMovies.single()
        assertEquals("The Matrix Reloaded", related.movie.primaryTitle)
        assertEquals(2L, related.sharedCastCount)
    }

    @Test
    fun `findMostProlificActors counts credits per person`(orm: ORMTemplate, capture: SqlCapture) {
        val principalRepository = orm.repository<PrincipalRepository>()
        val prolificActors = capture.execute {
            principalRepository.findMostProlificActors(10)
        }
        capture.printStatements("findMostProlificActors")
        // Keanu Reeves and Laurence Fishburne appear in two movies each.
        assertEquals(
            setOf("Keanu Reeves", "Laurence Fishburne"),
            prolificActors.take(2).map { it.person.primaryName }.toSet()
        )
        assertTrue(prolificActors.take(2).all { it.movieCount == 2L })
    }
}
