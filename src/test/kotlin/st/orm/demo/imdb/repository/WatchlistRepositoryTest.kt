package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.insertUser
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.model.WatchlistPk
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class WatchlistRepositoryTest {

    @Test
    fun `the toggle cycle contains-insert-contains-remove works on the pair key`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val user = orm.insertUser("visitor-1")
        // @StormTest rolls each test back, so the watchlist starts out empty
        // however the class orders its methods.
        val pulpFiction = movieRepository.getById("tt0110912")

        capture.record {
            assertFalse(watchlistRepository.contains(user, pulpFiction))

            watchlistRepository.insert(Watchlist(user = user, movie = pulpFiction, addedAt = Instant.now()))
            assertTrue(watchlistRepository.contains(user, pulpFiction))

            watchlistRepository.removeById(WatchlistPk(userId = user.id, movieId = pulpFiction.id))
            assertFalse(watchlistRepository.contains(user, pulpFiction))
        }
        capture.printStatements("watchlistToggle")
        assertEquals(1, capture.count(Operation.INSERT))
        assertEquals(1, capture.count(Operation.DELETE))
    }

    @Test
    fun `remove takes a movie off the watchlist whether or not it is on it`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val user = orm.insertUser("visitor-3")
        val matrix = movieRepository.getById("tt0133093")

        // Marking a movie seen removes it from the watchlist without checking first.
        watchlistRepository.remove(user, matrix)
        watchlistRepository.insert(Watchlist(user = user, movie = matrix, addedAt = Instant.now()))
        watchlistRepository.remove(user, matrix)

        assertFalse(watchlistRepository.contains(user, matrix))
    }

    @Test
    fun `a watchlist belongs to its user`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val alice = orm.insertUser("visitor-alice")
        val bob = orm.insertUser("visitor-bob")
        val matrix = movieRepository.getById("tt0133093")

        watchlistRepository.insert(Watchlist(user = alice, movie = matrix, addedAt = Instant.now()))

        assertTrue(watchlistRepository.contains(alice, matrix))
        assertFalse(watchlistRepository.contains(bob, matrix))
        assertEquals(0, watchlistRepository.findPage(bob, pageNumber = 0, pageSize = 2).totalCount())
    }

    @Test
    fun `findPage paginates the user's watchlist newest first`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val user = orm.insertUser("visitor-2")
        val baseInstant = Instant.parse("2026-07-01T10:00:00Z")
        listOf("tt0133093", "tt0111161", "tt0068646").forEachIndexed { index, movieId ->
            watchlistRepository.insert(
                Watchlist(user = user, movie = movieRepository.getById(movieId), addedAt = baseInstant.plusSeconds(index.toLong()))
            )
        }

        capture.clear()
        val page = capture.execute { watchlistRepository.findPage(user, pageNumber = 0, pageSize = 2) }
        capture.printStatements("findPage")
        assertEquals(2, page.content().size)
        assertEquals(3, page.totalCount())
        assertEquals(2, page.totalPages())
        assertTrue(page.hasNext())
        // Newest first: The Godfather was added last.
        assertEquals("The Godfather", page.content().first().movie.primaryTitle)
    }

    @Test
    fun `moveTo carries the entries the other user lacks and drops the rest`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val anonymous = orm.insertUser("visitor-anonymous")
        val signedIn = orm.insertUser("visitor-signed-in", name = "Alice")
        val matrix = movieRepository.getById("tt0133093")
        val godfather = movieRepository.getById("tt0068646")
        val addedAt = Instant.parse("2026-07-02T10:00:00Z")
        watchlistRepository.insert(Watchlist(user = anonymous, movie = matrix, addedAt = addedAt))
        watchlistRepository.insert(Watchlist(user = anonymous, movie = godfather, addedAt = addedAt))
        watchlistRepository.insert(Watchlist(user = signedIn, movie = godfather, addedAt = addedAt.minusSeconds(60)))

        watchlistRepository.moveTo(anonymous, signedIn)

        val moved = watchlistRepository.findPage(signedIn, pageNumber = 0, pageSize = 10).content()
        assertEquals(listOf("The Matrix", "The Godfather"), moved.map { it.movie.primaryTitle })
        // The Godfather kept the signed-in user's own, earlier moment.
        assertEquals(addedAt.minusSeconds(60), moved.last().addedAt)
        assertEquals(0, watchlistRepository.findPage(anonymous, pageNumber = 0, pageSize = 10).totalCount())
    }
}
