package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Badge
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.service.BadgeService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Duration
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class BadgeServiceTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun service(orm: ORMTemplate) = BadgeService(
        orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
        orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
        orm.repository(), orm.repository(), orm.repository()
    )

    @Test
    fun `badges count what is watched and checked in, are announced once, and are kept`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-badge", name = "Alice")
        val bob = orm.insertUser("bob-badge", name = "Bob")
        assertTrue(runBlocking { service.award(alice) }.isEmpty())

        val movies = orm.repository<MovieRepository>().findAll()
        orm.repository<MovieSeenRepository>().insert(movies.map { MovieSeen(alice, it, now) })
        // Two check-ins on one day, and a friend.
        orm.repository<CheckInRepository>().insert(listOf(CheckIn(alice, movies[0], now), CheckIn(alice, movies[1], now.plus(Duration.ofHours(2)))))
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)

        val earned = runBlocking { service.award(alice, now) }
        assertEquals(setOf(Badge.FIRST_REEL, Badge.CHECKED_IN, Badge.DOUBLE_BILL, Badge.FIRST_FRIEND), earned.toSet())
        // Announced once: the next look finds nothing new.
        assertTrue(runBlocking { service.award(alice, now) }.isEmpty())

        val wall = runBlocking { service.wallOf(alice, now) }
        assertEquals(4, wall.earnedCount)
        assertEquals(Badge.entries.size, wall.total)
        val nineties = wall.shelves.flatMap { it.badges }.first { it.badge == Badge.NINETIES }
        // The Matrix, The Shawshank Redemption and Pulp Fiction: three of ten.
        assertEquals("3 of 10", nineties.text)
        assertFalse(nineties.earned)

        // The friend gone, the badge stays.
        orm.repository<FriendshipRepository>().unfriend(alice, bob)
        assertTrue(runBlocking { service.wallOf(alice, now) }.shelves.flatMap { it.badges }.first { it.badge == Badge.FIRST_FRIEND }.earned)
    }
}
