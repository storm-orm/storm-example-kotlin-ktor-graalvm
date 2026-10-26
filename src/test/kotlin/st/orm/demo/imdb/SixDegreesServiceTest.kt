package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.service.SixDegreesService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class SixDegreesServiceTest {

    private fun service(orm: ORMTemplate) = SixDegreesService(
        orm.repository<PrincipalRepository>(),
        orm.repository<PersonRepository>(),
        orm.repository<MovieRepository>()
    )

    @Test
    fun `two actors of one movie are one step apart, and the same actor none`(orm: ORMTemplate) {
        val connection = runBlocking { service(orm).connect("nm0000206", "nm0000401") }!!
        assertTrue(connection.found)
        assertEquals(1, connection.degrees)
        assertEquals("Laurence Fishburne", connection.links!!.single().person.primaryName)
        assertEquals(0, runBlocking { service(orm).connect("nm0000206", "nm0000206") }!!.degrees)
        assertNull(runBlocking { service(orm).connect("nm0000206", "nm9999999") })
    }

    @Test
    fun `a chain runs through the movies two casts share, and none is found without one`(orm: ORMTemplate) {
        // Keanu Reeves and Tim Robbins share no movie, nor anyone who played with both.
        assertFalse(runBlocking { service(orm).connect("nm0000206", "nm0000209") }!!.found)

        // Morgan Freeman in The Matrix Reloaded links the Matrix movies to The Shawshank Redemption.
        val reloaded = orm.repository<MovieRepository>().getById("tt0234215")
        val freeman = orm.repository<PersonRepository>().getById("nm0000151")
        orm.repository<PrincipalRepository>().insert(Principal(reloaded.ref(), 3, freeman, "actor", "Somebody"))

        val connection = runBlocking { service(orm).connect("nm0000206", "nm0000209") }!!

        assertEquals(2, connection.degrees)
        assertEquals(listOf("The Matrix Reloaded", "The Shawshank Redemption"), connection.links!!.map { it.movie.primaryTitle })
        assertEquals(listOf("Morgan Freeman", "Tim Robbins"), connection.links!!.map { it.person.primaryName })
    }
}
