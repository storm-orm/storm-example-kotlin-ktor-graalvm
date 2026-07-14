package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.transaction
import st.orm.test.StormTest
import java.time.Instant

/**
 * Verifies the semantics of Storm's programmatic transactions used across
 * the application: the block is the boundary — an exception rolls back
 * every write, and setRollbackOnly() discards writes without one.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class TransactionTest {

    @Test
    fun `an exception inside the transaction rolls back all writes`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val godfather = movieRepository.getById("tt0068646")

        assertThrows<IllegalStateException> {
            runBlocking {
                transaction {
                    watchlistRepository.insert(Watchlist(movie = godfather, addedAt = Instant.now()))
                    check(false) { "Simulated failure after the insert" }
                }
            }
        }
        assertFalse(watchlistRepository.existsById(godfather))
    }

    @Test
    fun `setRollbackOnly discards the writes without an exception`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val pulpFiction = movieRepository.getById("tt0110912")

        runBlocking {
            transaction {
                watchlistRepository.insert(Watchlist(movie = pulpFiction, addedAt = Instant.now()))
                // Inside the transaction the write is visible...
                assertTrue(watchlistRepository.existsById(pulpFiction))
                setRollbackOnly()
            }
        }
        // ...and gone after the rollback.
        assertFalse(watchlistRepository.existsById(pulpFiction))
    }
}
