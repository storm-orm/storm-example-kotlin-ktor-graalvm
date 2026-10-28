package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.service.InsightsService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class InsightsServiceTest {

    private fun service(orm: ORMTemplate) = InsightsService(
        orm.repository<MovieSeenRepository>(),
        orm.repository<WatchlistRepository>(),
        orm.repository<MovieGenreRepository>(),
        orm.repository<PrincipalRepository>()
    )

    @Test
    fun `a visitor who has seen nothing gets the empty page`(orm: ORMTemplate) {
        val insights = runBlocking { service(orm).insightsFor(orm.insertUser("visitor-new")) }
        assertTrue(insights.isEmpty)
        assertNull(insights.ratings)
    }

    @Test
    fun `the seen list tells the favourites, the decades and the ratings watched`(orm: ORMTemplate) {
        val movies = orm.repository<MovieRepository>()
        val seen = orm.repository<MovieSeenRepository>()
        val user = orm.insertUser("visitor-insights")
        val at = Instant.parse("2026-07-01T10:00:00Z")
        // Five seen, so the range is known: both Matrix movies (one liked, one not), Shawshank, Pulp Fiction, The Godfather.
        seen.insert(MovieSeen(user, movies.getById("tt0133093"), at, liked = true))
        seen.insert(MovieSeen(user, movies.getById("tt0234215"), at, liked = false))
        listOf("tt0111161", "tt0110912", "tt0068646").forEach { seen.insert(MovieSeen(user, movies.getById(it), at)) }

        val insights = runBlocking { service(orm).insightsFor(user) }

        assertEquals(5, insights.seenCount)
        assertEquals((136 + 138 + 142 + 154 + 175) / 60L, insights.hoursWatched)
        assertEquals(1, insights.likedCount)
        assertEquals(1, insights.dislikedCount)
        assertEquals(1990, insights.favouriteDecade)
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne"), insights.actors.map { it.person.primaryName }.sorted())
        assertEquals(listOf("Lana Wachowski"), insights.directors.map { it.person.primaryName })
        assertEquals("Drama", insights.genres.first().item.genre.name)
        assertEquals(100.0, insights.genres.first().share)
        // The thumbs: the liked Matrix (8.7) against the disliked Reloaded (7.2).
        assertEquals(8.7, insights.likedAverage!!, 1e-9)
        assertEquals(7.2, insights.dislikedAverage!!, 1e-9)

        assertNotNull(insights.ratings)
        val ratings = insights.ratings!!
        assertEquals(5, ratings.bins.sumOf { it.count })
        assertEquals(5.0, ratings.from)
        assertEquals(10.0, ratings.to)
        assertEquals((5..10).toList(), ratings.ticks)
        // 9.2 and 9.3 share the bin from 9; the tallest bar or the curve's peak fills the chart.
        assertEquals(2, ratings.bins.first { it.from == 9.0 }.count)
        assertTrue(ratings.bins.maxOf { it.height } <= 100.0)
        assertEquals(61, ratings.curve!!.split(" ").size)
        assertEquals(8.66, ratings.range!!.mean, 0.01)

        // Standouts: each movie once, the best first.
        assertEquals("Highest rated", insights.highlights.first().label)
        assertEquals("The Shawshank Redemption", insights.highlights.first().movie.primaryTitle)
        assertEquals(insights.highlights.size, insights.highlights.map { it.movie.id }.distinct().size)
    }
}
