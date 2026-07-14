package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class GenreRepositoryTest {

    @Test
    fun `findByName looks up by the unique key`(orm: ORMTemplate, capture: SqlCapture) {
        val genreRepository = orm.repository<GenreRepository>()
        val sciFi = capture.execute { genreRepository.findByName("Sci-Fi") }
        capture.printStatements("findByName")
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals("Sci-Fi", sciFi?.name)
        assertNull(genreRepository.findByName("Musical"))
    }

    @Test
    fun `findGenresWithMovieCounts aggregates over the junction table`(orm: ORMTemplate, capture: SqlCapture) {
        val genreRepository = orm.repository<GenreRepository>()
        val genreCounts = capture.execute { genreRepository.findGenresWithMovieCounts() }
        capture.printStatements("findGenresWithMovieCounts")
        assertEquals(1, capture.count(Operation.SELECT))
        assertTrue(capture.statements().single().statement().contains("GROUP BY"))
        assertEquals(
            listOf("Action" to 2L, "Drama" to 3L, "Sci-Fi" to 2L),
            genreCounts.map { it.genre.name to it.movieCount }
        )
    }
}
