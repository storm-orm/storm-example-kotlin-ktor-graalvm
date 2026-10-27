package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Badge
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.Level
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.UserBadge
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserBadgeRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.LevelService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class LevelServiceTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun service(orm: ORMTemplate) = levelService(orm)

    @Test
    fun `a movie watched counts one, checked in one and a half, and the level reached is kept`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-level", name = "Alice")
        val movies = orm.repository<MovieRepository>().findAll()
        assertEquals(Level.EXTRA, runBlocking { service.progressOf(alice) }.level)
        assertEquals(setOf(Game.CHAIN, Game.CONNECT, Game.DRAFT, Game.CAST, Game.REEL), runBlocking { service.locksOf(alice) }.values.map { it.game }.toSet())
        assertEquals("Unlocks at Bit Part · 25 movies to go", runBlocking { service.locksOf(alice) }.getValue("CHAIN").text)

        orm.repository<MovieSeenRepository>().insert(movies.map { MovieSeen(alice, it, now) })
        // Two of the six checked in, one of them twice: 6 + 2 × ½ = 7.
        orm.repository<CheckInRepository>().insert(listOf(CheckIn(alice, movies[0], now), CheckIn(alice, movies[0], now), CheckIn(alice, movies[1], now)))
        val progress = runBlocking { service.progressOf(alice) }
        assertEquals(7.0, progress.score)
        assertEquals(6, progress.watched)
        assertEquals(2, progress.checkedIn)
        assertEquals(18, progress.toNext)
        assertEquals(Level.BIT_PART, progress.next)
        assertEquals(7.0 / 25, progress.fraction, 0.001)

        // Lifted to Supporting by hand, as by a long history, the level stays when the movies go.
        orm.repository<UserRepository>().update(orm.repository<UserRepository>().getById(alice.id).copy(level = Level.SUPPORTING))
        orm.repository<MovieSeenRepository>().findAll(alice).forEach { orm.repository<MovieSeenRepository>().remove(it) }
        val kept = runBlocking { service.progressOf(orm.repository<UserRepository>().getById(alice.id)) }
        assertEquals(Level.SUPPORTING, kept.level)
        assertTrue(runBlocking { service.canStart(orm.repository<UserRepository>().getById(alice.id), Game.CAST) })
        assertFalse(runBlocking { service.locksOf(orm.repository<UserRepository>().getById(alice.id)) }.containsKey("DRAFT"))
    }

    @Test
    fun `the points alone do not lift a level, its rites must be done and the people invited must have come`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-rites", name = "Alice")
        val movies = orm.repository<MovieRepository>().findAll()
        // Thirty points, from six movies checked in five times... no: six watched and twenty-four more as history is
        // not in the fixture, so the points come from check-ins of the six: 6 + 6 × ½ = 9, lifted by hand below.
        orm.repository<MovieSeenRepository>().insert(movies.map { MovieSeen(alice, it, now) })
        orm.repository<CheckInRepository>().insert(movies.map { CheckIn(alice, it, now) })
        val users = orm.repository<UserRepository>()
        var progress = runBlocking { service.progressOf(users.getById(alice.id)) }
        assertEquals(Level.EXTRA, progress.level)
        assertFalse(progress.held)

        // With the points of Bit Part but none of its rites, the level is held back, and says what is left.
        orm.repository<MovieSeenRepository>().insert((1..20).map { i -> MovieSeen(alice, orm.repository<MovieRepository>().insertAndFetch(st.orm.demo.imdb.model.Movie("tt77700$i", "Extra $i", "Extra $i", 2000, 100)), now) })
        progress = runBlocking { service.progressOf(users.getById(alice.id)) }
        assertEquals(Level.BIT_PART, progress.byPoints)
        assertEquals(Level.EXTRA, progress.level)
        assertTrue(progress.held)
        assertEquals("2 rites to go for Bit Part", progress.nextText)

        // The rites done, the level follows.
        orm.repository<UserBadgeRepository>().insert(listOf(UserBadge(alice, Badge.DECK_DONE, now), UserBadge(alice, Badge.HOT_STREAK, now)))
        progress = runBlocking { service.progressOf(users.getById(alice.id)) }
        assertEquals(Level.BIT_PART, progress.level)
        assertFalse(progress.held)
        // Supporting asks for someone invited too: the ladder says so.
        assertEquals(listOf("Chain Gang", "First Daily", "0 of 1 invited"), progress.rites.map { it.text })
    }

    @Test
    fun `the levels page lays the ladder out with the visitor's rung lit and what each unlocks`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-ladder", name = "Alice")
        val view = runBlocking { service(orm).levelsOf(alice) }
        assertEquals(Level.entries.size, view.rungs.size)
        assertTrue(view.rungs.first().current)
        assertEquals(listOf("Top Gun", "Love at First Sight"), view.rungs.first().unlocks.map { it.title })
        assertEquals(listOf("Chain Reaction", "Six Degrees of Separation"), view.rungs[1].unlocks.map { it.title })
        assertFalse(view.rungs[1].reached)
    }
}
