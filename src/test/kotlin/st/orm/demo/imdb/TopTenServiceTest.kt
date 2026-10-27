package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.TopTenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.TopTenService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class TopTenServiceTest {

    private fun service(orm: ORMTemplate) = TopTenService(
        orm.repository<TopTenRepository>(), orm.repository<MovieRepository>(), orm.repository<MovieSeenRepository>(), movieSeenService(orm), orm.repository<UserRepository>()
    )

    @Test
    fun `a movie put in the top ten counts as watched, the list is dragged into order, and one taken out closes the gap`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-top", name = "Alice")
        val seen = orm.repository<MovieSeenRepository>()
        val movies = orm.repository<MovieRepository>()

        runBlocking { service.add(alice, "tt0068646") }
        runBlocking { service.add(alice, "tt0133093") }
        runBlocking { service.add(alice, "tt0110912") }
        // Put in twice changes nothing.
        runBlocking { service.add(alice, "tt0133093") }
        var view = runBlocking { service.topTenOf(alice) }
        assertEquals(listOf("tt0068646", "tt0133093", "tt0110912"), view.entries.map { it.movie.id })
        assertEquals(listOf(1, 2, 3), view.entries.map { it.position })
        assertEquals((4..10).toList(), view.open)
        assertTrue(seen.contains(alice, movies.getById("tt0068646")))

        runBlocking { service.reorder(alice, listOf("tt0110912", "tt0068646", "tt0133093")) }
        view = runBlocking { service.topTenOf(alice) }
        assertEquals(listOf("tt0110912", "tt0068646", "tt0133093"), view.entries.map { it.movie.id })
        // An order of other movies changes nothing.
        runBlocking { service.reorder(alice, listOf("tt0110912", "tt0068646")) }
        assertEquals(3, runBlocking { service.topTenOf(alice) }.entries.size)

        runBlocking { service.remove(alice, "tt0068646") }
        view = runBlocking { service.topTenOf(alice) }
        assertEquals(listOf("tt0110912", "tt0133093"), view.entries.map { it.movie.id })
        assertEquals(listOf(1, 2), view.entries.map { it.position })
    }
}
