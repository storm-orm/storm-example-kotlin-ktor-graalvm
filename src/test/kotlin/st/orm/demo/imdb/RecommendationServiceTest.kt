package st.orm.demo.imdb

import st.orm.demo.imdb.repository.CheckInRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.ReasonKind
import st.orm.demo.imdb.model.RecommendationEventKind
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
import st.orm.demo.imdb.repository.RecommendationEventRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.service.RecommendationService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant
import java.time.Year
import java.time.ZoneOffset

/**
 * The engine against the fixture: a visitor who liked The Matrix gets its
 * sequel first, with the reasons and the seed that say why; the seen movie
 * itself is never recommended; a visitor without lists gets the catalogue's
 * picks; and only a recommended movie leaves an event.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class RecommendationServiceTest {

    @Test
    fun `a liked movie brings its people and genres, and never itself`(orm: ORMTemplate) {
        val service = service(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val recommendationRepository = orm.repository<RecommendationRepository>()
        val user = orm.insertUser("visitor-matrix")
        val matrix = movieRepository.getById("tt0133093")
        orm.repository<MovieSeenRepository>().insert(MovieSeen(user, matrix, Instant.now(), liked = true))

        runBlocking { service.refresh(user.id) }

        val recommendations = recommendationRepository.findAll(user)
        assertFalse(recommendations.any { it.movie.id == matrix.id })
        val first = recommendations.first()
        assertEquals("The Matrix Reloaded", first.movie.primaryTitle)
        assertEquals(matrix.id, first.seedMovie?.id)
        // The director weighs more than the lead, so the reason names her.
        assertEquals(listOf("Because you saw The Matrix", "Directed by Lana Wachowski"), first.reasons.map { it.text })
        // The rest of the catalogue follows on quality alone, with no seed.
        assertTrue(recommendations.drop(1).all { it.seedMovie == null })
        assertEquals(5, recommendations.size)
    }

    @Test
    fun `the home sections come from the stored rows, with a shelf per seed`(orm: ORMTemplate) {
        val service = service(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val user = orm.insertUser("visitor-home")
        orm.repository<MovieSeenRepository>().insert(MovieSeen(user, movieRepository.getById("tt0133093"), Instant.now(), liked = true))
        runBlocking { service.refresh(user.id) }

        val home = runBlocking { service.forHome(user) }
        val page = runBlocking { service.forYou(user) }

        assertTrue(home.personal)
        assertEquals("The Matrix Reloaded", home.watchNext.first().movie.primaryTitle)
        assertEquals(listOf("The Matrix"), home.shelves.map { it.seed.primaryTitle })
        assertEquals("Because you saw The Matrix", home.shelves.single().title)
        assertEquals(listOf("The Matrix Reloaded"), home.shelves.single().movies.map { it.movie.primaryTitle })
        assertTrue(page.personal)
        assertEquals(home.watchNext.map { it.movie.id }, page.movies.map { it.movie.id })
        assertNull(page.nextCursor)
    }

    @Test
    fun `a visitor without lists gets the catalogue's picks`(orm: ORMTemplate) {
        val service = service(orm)
        val user = orm.insertUser("visitor-new")

        val home = runBlocking { service.forHome(user) }
        val page = runBlocking { service.forYou(user) }

        assertFalse(home.personal)
        assertTrue(home.shelves.isEmpty())
        assertEquals(6, page.movies.size)
        assertTrue(page.movies.all { pick -> pick.reasons.all { it.kind == ReasonKind.RATING } })
        // The best-rated movie leads: nothing personal weighs in.
        assertEquals("The Shawshank Redemption", page.movies.first().movie.primaryTitle)
        assertTrue(orm.repository<RecommendationRepository>().findAll(user).isEmpty())
    }

    @Test
    fun `a thumbs down on the seed turns its sequel away`(orm: ORMTemplate) {
        val service = service(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val user = orm.insertUser("visitor-disliked")
        orm.repository<MovieSeenRepository>().insert(MovieSeen(user, movieRepository.getById("tt0133093"), Instant.now(), liked = false))

        runBlocking { service.refresh(user.id) }

        val recommendations = orm.repository<RecommendationRepository>().findAll(user)
        assertEquals("The Matrix Reloaded", recommendations.last().movie.primaryTitle)
    }

    @Test
    fun `only a recommended movie leaves an event`(orm: ORMTemplate) {
        val service = service(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val eventRepository = orm.repository<RecommendationEventRepository>()
        val user = orm.insertUser("visitor-events")
        orm.repository<WatchlistRepository>().insert(Watchlist(user, movieRepository.getById("tt0133093"), Instant.now()))
        runBlocking { service.refresh(user.id) }

        service.record(user, movieRepository.getById("tt0234215"), RecommendationEventKind.OPENED)
        service.record(user, movieRepository.getById("tt0234215"), RecommendationEventKind.SAVED)
        service.record(user, movieRepository.getById("tt0133093"), RecommendationEventKind.SEEN)

        val counts = eventRepository.countByKind()
        assertEquals(listOf(RecommendationEventKind.OPENED to 1L, RecommendationEventKind.SAVED to 1L), counts.map { it.kind to it.count })
    }

    @Test
    fun `showing the picks counts, the counts survive a recomputation, and an opened pick starts afresh`(orm: ORMTemplate) {
        val service = service(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val recommendationRepository = orm.repository<RecommendationRepository>()
        val user = orm.insertUser("visitor-shown")
        orm.repository<MovieSeenRepository>().insert(MovieSeen(user, movieRepository.getById("tt0133093"), Instant.now(), liked = true))
        runBlocking { service.refresh(user.id) }

        runBlocking { service.forHome(user) }
        runBlocking { service.forYou(user) }
        val shownTwice = recommendationRepository.findAll(user)
        assertTrue(shownTwice.all { it.shownCount == 2 && it.lastShownAt != null })

        runBlocking { service.refresh(user.id) }
        assertTrue(recommendationRepository.findAll(user).all { it.shownCount == 2 })

        val reloaded = movieRepository.getById("tt0234215")
        service.record(user, reloaded, RecommendationEventKind.OPENED)
        assertEquals(0, recommendationRepository.findAll(user).first { it.movie.id == reloaded.id }.shownCount)
    }

    @Test
    fun `a dismissed pick leaves the list at once and stays out, and the dismissal counts against its kind`(orm: ORMTemplate) {
        val service = service(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val recommendationRepository = orm.repository<RecommendationRepository>()
        val user = orm.insertUser("visitor-dismiss")
        orm.repository<MovieSeenRepository>().insert(MovieSeen(user, movieRepository.getById("tt0133093"), Instant.now(), liked = true))
        runBlocking { service.refresh(user.id) }
        assertEquals("The Matrix Reloaded", recommendationRepository.findAll(user).first().movie.primaryTitle)

        runBlocking { service.dismiss(user, "tt0234215") }
        assertFalse(recommendationRepository.findAll(user).any { it.movie.id == "tt0234215" })

        runBlocking { service.refresh(user.id) }
        val recommendations = recommendationRepository.findAll(user)
        assertFalse(recommendations.any { it.movie.id == "tt0234215" })
        assertEquals(4, recommendations.size)
        assertEquals(listOf(RecommendationEventKind.DISMISSED to 1L), orm.repository<RecommendationEventRepository>().countByKind().map { it.kind to it.count })
    }

    @Test
    fun `a seed's weight halves every ninety days, down to a quarter`() {
        val now = Instant.parse("2026-10-23T12:00:00Z")

        assertEquals(1.0, RecommendationService.recencyFactor(now, now))
        assertEquals(0.5, RecommendationService.recencyFactor(now.minus(java.time.Duration.ofDays(90)), now), 0.001)
        assertEquals(0.25, RecommendationService.recencyFactor(now.minus(java.time.Duration.ofDays(180)), now), 0.001)
        assertEquals(0.25, RecommendationService.recencyFactor(now.minus(java.time.Duration.ofDays(1000)), now))
        // A moment in the future weighs as now.
        assertEquals(1.0, RecommendationService.recencyFactor(now.plusSeconds(60), now))
    }

    private fun service(orm: ORMTemplate) = RecommendationService(
        orm.repository<UserRepository>(),
        orm.repository<MovieRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<WatchlistRepository>(),
        orm.repository<RecommendationDismissalRepository>(),
        orm.repository<MovieAdditionRepository>(),
        orm.repository<MovieGenreRepository>(),
        orm.repository<PrincipalRepository>(),
        orm.repository<RatingRepository>(),
        orm.repository<MovieViewRepository>(),
        orm.repository<RecommendationRepository>(),
        orm.repository<RecommendationEventRepository>(),
            orm.repository<CheckInRepository>()
    )

    @Test
    fun `a movie that joined lately is pushed and shown as new since the last visit, only when it fits`(orm: ORMTemplate) {
        val service = service(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val additions = orm.repository<MovieAdditionRepository>()
        val user = orm.insertUser("visitor-arrivals")
        orm.repository<MovieSeenRepository>().insert(MovieSeen(user, movieRepository.getById("tt0111161"), Instant.now(), liked = true))
        // Amélie, a release of this year for the test, joined the catalogue yesterday, long after the fixture's other additions in July.
        val thisYearsAmelie = movieRepository.updateAndFetch(movieRepository.getById("tt0211915").copy(startYear = Year.now(ZoneOffset.UTC).value))
        additions.insert(MovieAddition(thisYearsAmelie, Instant.now().minus(java.time.Duration.ofDays(1))))

        runBlocking { service.refresh(user.id) }
        val page = runBlocking { service.forYou(user) }

        val amelie = page.movies.first { it.movie.id == "tt0211915" }
        assertTrue(amelie.reasons.any { it.kind == ReasonKind.NEW })
        assertEquals(listOf("Amélie"), page.newArrivals.map { it.movie.primaryTitle })
        // The fixture account was last seen in July: the strip reaches back to the quarter's ceiling.
        assertEquals(90L, page.newSince.toDays())
        // A dismissed arrival no longer fits, and the strip goes with it.
        runBlocking { service.dismiss(user, "tt0211915") }
        runBlocking { service.refresh(user.id) }
        assertTrue(runBlocking { service.forYou(user) }.newArrivals.isEmpty())
    }
}
