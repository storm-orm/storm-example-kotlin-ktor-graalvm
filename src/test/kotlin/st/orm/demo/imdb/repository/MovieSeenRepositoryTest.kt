package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.insertUser
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieSeenRepositoryTest {

    @Test
    fun `a seen list belongs to its user and scrolls the most recently marked first`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val movieSeenRepository = orm.repository<MovieSeenRepository>()
        val alice = orm.insertUser("visitor-alice")
        val bob = orm.insertUser("visitor-bob")
        val baseInstant = Instant.parse("2026-07-01T10:00:00Z")
        listOf("tt0133093", "tt0111161", "tt0068646").forEachIndexed { index, movieId ->
            movieSeenRepository.insert(
                MovieSeen(user = alice, movie = movieRepository.getById(movieId), seenAt = baseInstant.plusSeconds(index.toLong()))
            )
        }

        assertTrue(movieSeenRepository.contains(alice, movieRepository.getById("tt0133093")))
        assertFalse(movieSeenRepository.contains(bob, movieRepository.getById("tt0133093")))

        capture.clear()
        val window = capture.execute { movieSeenRepository.scroll(alice, MovieSeenRepository.newestFirst(2)) }
        capture.printStatements("scroll")
        assertEquals(3, movieSeenRepository.countFor(alice))
        assertEquals(listOf("The Godfather", "The Shawshank Redemption"), window.content().map { it.movie.primaryTitle })
        assertEquals(0, movieSeenRepository.countFor(bob))
    }

    @Test
    fun `moveTo carries the entries the other user lacks and drops the rest`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        val movieSeenRepository = orm.repository<MovieSeenRepository>()
        val anonymous = orm.insertUser("visitor-anonymous")
        val signedIn = orm.insertUser("visitor-signed-in", name = "Alice")
        val matrix = movieRepository.getById("tt0133093")
        val godfather = movieRepository.getById("tt0068646")
        val seenAt = Instant.parse("2026-07-02T10:00:00Z")
        movieSeenRepository.insert(MovieSeen(user = anonymous, movie = matrix, seenAt = seenAt))
        movieSeenRepository.insert(MovieSeen(user = anonymous, movie = godfather, seenAt = seenAt))
        movieSeenRepository.insert(MovieSeen(user = signedIn, movie = godfather, seenAt = seenAt.minusSeconds(60)))

        movieSeenRepository.moveTo(anonymous, signedIn)

        val moved = movieSeenRepository.scroll(signedIn, MovieSeenRepository.newestFirst(10)).content()
        assertEquals(listOf("The Matrix", "The Godfather"), moved.map { it.movie.primaryTitle })
        assertEquals(0, movieSeenRepository.countFor(anonymous))
    }
}
