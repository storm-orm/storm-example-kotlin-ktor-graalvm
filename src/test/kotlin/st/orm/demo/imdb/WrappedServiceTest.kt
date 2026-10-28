package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.MovieCountry
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.YearShareRepository
import st.orm.demo.imdb.service.WrappedService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class WrappedServiceTest {

    private fun service(orm: ORMTemplate) = WrappedService(
        orm.repository<CheckInRepository>(),
        orm.repository<RatingRepository>(),
        orm.repository<MovieGenreRepository>(),
        orm.repository<PrincipalRepository>(),
        orm.repository<MovieCountryRepository>(),
        orm.repository<MovieLanguageRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<YearShareRepository>()
    )

    private fun checkIn(orm: ORMTemplate, user: User, movieId: String, at: String) {
        orm.repository<CheckInRepository>().insert(CheckIn(user, orm.repository<MovieRepository>().getById(movieId), Instant.parse(at)))
    }

    @Test
    fun `a year tells its movies, months, streak, favourites and places from its check-ins alone`(orm: ORMTemplate) {
        val user = orm.insertUser("visitor-year", name = "Visitor")
        // The Matrix in December of the year before: watching it again this year is a rewatch.
        checkIn(orm, user, "tt0133093", "2025-12-20T20:00:00Z")
        checkIn(orm, user, "tt0133093", "2026-03-06T20:00:00Z")
        checkIn(orm, user, "tt0234215", "2026-03-07T20:00:00Z")
        checkIn(orm, user, "tt0111161", "2026-03-08T20:00:00Z")
        checkIn(orm, user, "tt0068646", "2026-07-03T20:00:00Z")
        // The next year's first check-in is not this year's.
        checkIn(orm, user, "tt0110912", "2027-01-01T00:00:00Z")
        val matrix = orm.repository<MovieRepository>().getById("tt0133093").ref()
        val reloaded = orm.repository<MovieRepository>().getById("tt0234215").ref()
        orm.repository<MovieCountryRepository>().insert(listOf(MovieCountry(matrix, "US"), MovieCountry(matrix, "AU"), MovieCountry(reloaded, "US")))
        orm.repository<MovieLanguageRepository>().insert(listOf(MovieLanguage(matrix, "en"), MovieLanguage(reloaded, "en")))

        val year = runBlocking { service(orm).yearOf(user, 2026) }

        assertEquals(4, year.checkIns)
        assertEquals(4, year.movies)
        assertEquals(1, year.rewatches)
        assertEquals(3, year.firstWatches)
        assertEquals((136 + 138 + 142 + 175) / 60L, year.hours)
        assertEquals("Mar", year.busiestMonth!!.month)
        assertEquals(3L, year.busiestMonth!!.checkIns)
        assertEquals(12, year.months.size)
        assertEquals(3, year.streak!!.days)
        assertEquals("6 – 8 Mar", year.streak!!.caption)
        // Both Matrix movies, with Keanu Reeves and Lana Wachowski in each; Shawshank and The Godfather are Drama.
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne"), year.actors.map { it.person.primaryName }.sorted())
        assertEquals(listOf("Lana Wachowski"), year.directors.map { it.person.primaryName })
        assertEquals(setOf("Action", "Sci-Fi", "Drama"), year.genres.map { it.item.genre.name }.toSet())
        assertTrue(year.genres.all { it.count == 2L })
        // The United States has both Matrix movies, Australia the first: each movie once per country.
        assertEquals(listOf("United States" to 2L, "Australia" to 1L), year.countries.map { it.name to it.movies })
        assertEquals("🇺🇸", year.countries.first().flag)
        assertEquals(listOf("English" to 2L), year.languages.map { it.name to it.movies })
        assertEquals("First of the year", year.standouts.first().label)
        assertEquals("The Matrix", year.standouts.first().movie.primaryTitle)
        assertEquals(year.standouts.size, year.standouts.map { it.movie.id }.distinct().size)
        // The years run from the first check-in to this one, the latest first.
        assertEquals(2025, year.years.last())
        assertTrue(2026 in year.years)
        assertTrue(year.friends.isEmpty())
    }

    @Test
    fun `a year without check-ins is empty, and a friend's year counts only when they share the days`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-year", name = "Alice")
        val bob = orm.insertUser("bob-year", name = "Bob")
        val carol = orm.insertUser("carol-year", name = "Carol")
        orm.repository<UserRepository>().update(carol.copy(shareDates = false))
        val friendships = orm.repository<FriendshipRepository>()
        friendships.befriend(alice, bob, Instant.now())
        friendships.befriend(alice, orm.repository<UserRepository>().getById("carol-year"), Instant.now())
        assertTrue(runBlocking { service(orm).yearOf(alice, 2026) }.isEmpty)

        checkIn(orm, alice, "tt0133093", "2026-05-01T20:00:00Z")
        checkIn(orm, bob, "tt0111161", "2026-05-01T20:00:00Z")
        checkIn(orm, bob, "tt0110912", "2026-06-01T20:00:00Z")
        checkIn(orm, carol, "tt0068646", "2026-06-01T20:00:00Z")

        val year = runBlocking { service(orm).yearOf(alice, 2026) }

        assertEquals(listOf("Bob" to 2L, "You" to 1L), year.friends.map { it.name to it.checkIns })
        assertEquals(100.0, year.friends.first().share)
        assertNull(year.streak)
    }

    @Test
    fun `a shared year is the same link each time and shows without the friends`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-shared", name = "Alice")
        val bob = orm.insertUser("bob-shared", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, Instant.now())
        checkIn(orm, alice, "tt0133093", "2026-05-01T20:00:00Z")
        val service = service(orm)

        val token = runBlocking { service.share(alice, 2026) }
        assertEquals(token, runBlocking { service.share(alice, 2026) })

        val shared = runBlocking { service.shared(token) }
        assertNotNull(shared)
        assertEquals("Alice", shared!!.owner.name)
        assertEquals(1, shared.movies)
        assertTrue(shared.friends.isEmpty())
        assertNull(runBlocking { service.shared("no-such-token") })
    }
}
