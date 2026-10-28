package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieAdditionRepositoryTest {

    @Test
    fun `scrollAdditions scrolls the additions newest first, most voted within a moment`(orm: ORMTemplate, capture: SqlCapture) {
        val movieAdditionRepository = orm.repository<MovieAdditionRepository>()

        val firstWindow = capture.execute {
            movieAdditionRepository.scrollAdditions(fromYear = 1994, filter = null, scrollable = MovieAdditionRepository.newestFirst(3))
        }
        capture.printStatements("scrollAdditions")
        assertEquals(1, capture.count(Operation.SELECT))
        val statement = capture.statements().single().statement()
        // The movie comes with the addition; the rating is joined for the order only.
        assertTrue(statement.contains("INNER JOIN movie m"))
        assertTrue(statement.contains("m.start_year >= ?"))
        assertTrue(statement.contains("ORDER BY ma.added_at DESC, r.vote_count DESC, ma.movie_id"))
        // 3 July: The Matrix (2M votes) before Reloaded (600k); 2 July: Shawshank (2.9M) before Pulp Fiction (2.2M).
        assertEquals(
            listOf("The Matrix", "The Matrix Reloaded", "The Shawshank Redemption"),
            firstWindow.content().map { it.movie.primaryTitle }
        )
        assertEquals(Instant.parse("2026-07-03T03:00:00Z"), firstWindow.content().first().addedAt)
        assertTrue(firstWindow.hasNext())

        // The cursor resumes inside 2 July: the keyset holds the moment, the votes and the movie.
        val secondWindow = movieAdditionRepository.scrollAdditions(
            fromYear = 1994,
            filter = null,
            scrollable = MovieAdditionRepository.newestFirst(3).from(firstWindow.nextCursor()!!)
        )
        // The Godfather and Amélie were there from the start: no addition.
        assertEquals(listOf("Pulp Fiction"), secondWindow.content().map { it.movie.primaryTitle })
        assertFalse(secondWindow.hasNext())
    }

    @Test
    fun `the additions keep to the releases from a year on`(orm: ORMTemplate) {
        val movieAdditionRepository = orm.repository<MovieAdditionRepository>()

        // Shawshank and Pulp Fiction (1994) joined the catalogue, but they are no recent releases.
        val window = movieAdditionRepository.scrollAdditions(fromYear = 1999, filter = null, scrollable = MovieAdditionRepository.newestFirst(3))
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), window.content().map { it.movie.primaryTitle })
        assertFalse(window.hasNext())

        val since = Instant.parse("2026-07-01T00:00:00Z")
        assertEquals(
            listOf("The Matrix Reloaded"),
            movieAdditionRepository.findAddedSince(since, fromYear = 2000, limit = 10).map { it.movie.primaryTitle }
        )
        assertTrue(movieAdditionRepository.findAddedSince(since, fromYear = 2004, limit = 10).isEmpty())
    }
}
