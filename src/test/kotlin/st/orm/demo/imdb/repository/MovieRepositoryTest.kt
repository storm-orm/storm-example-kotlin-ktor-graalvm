package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieRepositoryTest {

    @Test
    fun `countMoviesPerDecade groups by the computed decade`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val decades = capture.execute {
            movieRepository.countMoviesPerDecade()
        }
        capture.printStatements("countMoviesPerDecade")
        assertTrue(capture.statements().single().statement().contains("GROUP BY"))
        assertEquals(listOf(1970 to 1L, 1990 to 3L, 2000 to 1L), decades.map { it.decade to it.movieCount })
    }
}
