package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.service.RecentlyAddedService
import st.orm.demo.imdb.service.recentYear
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class RecentlyAddedServiceTest {

    @Test
    fun `a release is recent from this year on, or from last year for a month into the new one`() {
        assertEquals(2026, recentYear(Instant.parse("2026-10-24T12:00:00Z")))
        assertEquals(2026, recentYear(Instant.parse("2026-02-05T12:00:00Z")))
        assertEquals(2025, recentYear(Instant.parse("2026-01-05T12:00:00Z")))
        assertEquals(2025, recentYear(Instant.parse("2026-01-31T23:59:59Z")))
    }

    @Test
    fun `the page lists the recent releases that joined the catalogue, by day`(orm: ORMTemplate) {
        val service = RecentlyAddedService(orm.repository<MovieAdditionRepository>())

        // Seen from 2003, Reloaded is the one recent release among the four additions.
        val page = runBlocking { service.recentlyAdded(now = Instant.parse("2003-06-01T00:00:00Z")) }
        assertEquals(listOf("3 July 2026"), page.days.map { it.label })
        assertEquals(listOf("The Matrix Reloaded"), page.days.single().movies.map { it.primaryTitle })
        assertNull(page.nextCursor)

        // Seen from January 1999, the releases of 1998 on count: The Matrix and Reloaded, not the two of 1994.
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"),
            runBlocking { service.recentlyAdded(now = Instant.parse("1999-01-05T00:00:00Z")) }.days.flatMap { it.movies }.map { it.primaryTitle })
    }
}
