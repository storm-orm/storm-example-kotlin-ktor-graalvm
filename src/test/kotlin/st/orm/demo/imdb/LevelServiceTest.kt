package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.Level
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.LevelService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class LevelServiceTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun service(orm: ORMTemplate) = LevelService(orm.repository(), orm.repository(), orm.repository())

    @Test
    fun `a movie watched counts one, checked in one and a half, and the level reached is kept`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-level", name = "Alice")
        val movies = orm.repository<MovieRepository>().findAll()
        assertEquals(Level.EXTRA, runBlocking { service.progressOf(alice) }.level)
        assertEquals(setOf(Game.CHAIN, Game.CONNECT, Game.DRAFT, Game.CAST), runBlocking { service.locksOf(alice) }.values.map { it.game }.toSet())
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
    fun `the levels page lays the ladder out with the visitor's rung lit and what each unlocks`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-ladder", name = "Alice")
        val view = runBlocking { service(orm).levelsOf(alice) }
        assertEquals(Level.entries.size, view.rungs.size)
        assertTrue(view.rungs.first().current)
        assertEquals(listOf("Higher or Lower", "Swipe"), view.rungs.first().unlocks.map { it.title })
        assertEquals(listOf("Movie Chain", "Connect"), view.rungs[1].unlocks.map { it.title })
        assertFalse(view.rungs[1].reached)
    }
}
