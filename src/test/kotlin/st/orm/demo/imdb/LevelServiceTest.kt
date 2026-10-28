package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Badge
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.service.LevelRites
import st.orm.demo.imdb.service.GameRules
import st.orm.demo.imdb.model.DailyGame
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
    fun `a movie watched counts one, a check-in as watched, and the level reached is kept`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-level", name = "Alice")
        val movies = orm.repository<MovieRepository>().findAll()
        assertEquals(Level.EXTRA, runBlocking { service.progressOf(alice, fresh = true) }.level)
        assertEquals(setOf("CHAIN", "CONNECT", "DRAFT", "CAST", "REEL", "CONNECTIONS", "USUAL_SUSPECTS", "MEMENTO", "THE_MASK", "AMADEUS"), runBlocking { service.locksOf(alice) }.keys)
        assertEquals("Unlocks at Bit Part · 25 movies to go", runBlocking { service.locksOf(alice) }.getValue("CHAIN").text)

        orm.repository<MovieSeenRepository>().insert(movies.map { MovieSeen(alice, it, now) })
        // Two of the six checked in, one of them twice: the score is the six watched.
        orm.repository<CheckInRepository>().insert(listOf(CheckIn(alice, movies[0], now), CheckIn(alice, movies[0], now), CheckIn(alice, movies[1], now)))
        val progress = runBlocking { service.progressOf(alice, fresh = true) }
        assertEquals(6.0, progress.score)
        assertEquals(6, progress.watched)
        assertEquals(2, progress.checkedIn)
        assertEquals(19, progress.toNext)
        assertEquals(Level.BIT_PART, progress.next)
        assertEquals(6.0 / 25, progress.fraction, 0.001)

        // Lifted to Supporting by hand, as by a long history, the level stays when the movies go.
        orm.repository<UserRepository>().update(orm.repository<UserRepository>().getById(alice.id).copy(level = Level.SUPPORTING))
        orm.repository<MovieSeenRepository>().findAll(alice).forEach { orm.repository<MovieSeenRepository>().remove(it) }
        val kept = runBlocking { service.progressOf(orm.repository<UserRepository>().getById(alice.id), fresh = true) }
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
        var progress = runBlocking { service.progressOf(users.getById(alice.id), fresh = true) }
        assertEquals(Level.EXTRA, progress.level)
        assertFalse(progress.held)

        // With the points of Bit Part but none of its rites, the level is held back, and says what is left.
        orm.repository<MovieSeenRepository>().insert((1..20).map { i -> MovieSeen(alice, orm.repository<MovieRepository>().insertAndFetch(st.orm.demo.imdb.model.Movie("tt77700$i", "Extra $i", "Extra $i", 2000, 100)), now) })
        progress = runBlocking { service.progressOf(users.getById(alice.id), fresh = true) }
        assertEquals(Level.BIT_PART, progress.byPoints)
        assertEquals(Level.EXTRA, progress.level)
        assertTrue(progress.held)
        assertEquals("3 rites to go for Bit Part", progress.nextText)

        // The rites done, the level follows.
        orm.repository<UserBadgeRepository>().insert(listOf(UserBadge(alice, Badge.DECK_DONE, now), UserBadge(alice, Badge.HOT_STREAK, now), UserBadge(alice, Badge.CHECKED_IN, now)))
        progress = runBlocking { service.progressOf(users.getById(alice.id), fresh = true) }
        assertEquals(Level.BIT_PART, progress.level)
        assertFalse(progress.held)
        // Supporting asks for someone invited too: the ladder says so.
        assertEquals(listOf("Chain Reaction", "First Daily", "Double Bill", "0 of 1 invited"), progress.rites.map { it.text })
    }

    @Test
    fun `the levels page lays the ladder out with the visitor's rung lit and what each unlocks`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-ladder", name = "Alice")
        val view = runBlocking { service(orm).levelsOf(alice) }
        assertEquals(Level.entries.size, view.rungs.size)
        assertTrue(view.rungs.first().current)
        assertEquals(listOf("Top Gun", "Love at First Sight", "The Outsiders", "Face/Off"), view.rungs.first().unlocks)
        assertEquals(listOf("Chain Reaction", "Six Degrees of Separation", "The Breakfast Club"), view.rungs[1].unlocks)
        assertFalse(view.rungs[1].reached)
    }

    @Test
    fun `no level is held by a game it has not unlocked, every rite being earned below its level`() {
        // Where each badge is earned: the game, or nothing, for the check-ins, the company and the sponsorship.
        val earnedIn = mapOf(
            Badge.DECK_DONE to Game.SWIPE, Badge.HOT_STREAK to Game.HIGHER_LOWER, Badge.WINNER to Game.HIGHER_LOWER, Badge.CHAMPION to Game.HIGHER_LOWER,
            Badge.CHAIN_GANG to Game.CHAIN, Badge.BACON_NUMBER to Game.CHAIN, Badge.WILLIS_NUMBER to Game.CHAIN, Badge.NICHOLSON_NUMBER to Game.CHAIN, Badge.STREEP_NUMBER to Game.CHAIN,
            Badge.CAST_CALL to Game.CAST
        )
        val earnedInDaily = mapOf(
            Badge.FIRST_DAILY to DailyGame.ODD_ONE_OUT, Badge.DAILY_WEEK to DailyGame.ODD_ONE_OUT, Badge.ODD_EYE to DailyGame.ODD_ONE_OUT,
            Badge.PERFECT_GRID to DailyGame.CONNECTIONS, Badge.YOU_ARE_ENOUGH to DailyGame.THE_MASK
        )
        for (level in Level.entries) for (badge in LevelRites.of(level).badges) {
            val needs = earnedIn[badge]?.let { GameRules.of(it).level } ?: earnedInDaily[badge]?.let { GameRules.of(it).level } ?: Level.EXTRA
            assertTrue(needs.ordinal < level.ordinal, "${badge.title}, a rite of ${level.label}, is earned in a game of ${needs.label}")
        }
    }
}
