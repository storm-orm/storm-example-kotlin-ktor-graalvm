package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import st.orm.Scrollable
import st.orm.demo.imdb.model.PersonSummary_
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class PersonSummaryRepositoryTest {

    @Test
    fun `searchByName selects only the projected columns`(orm: ORMTemplate, capture: SqlCapture) {
        val personSummaryRepository = orm.repository<PersonSummaryRepository>()
        val window = capture.execute {
            personSummaryRepository.searchByName("FREEMAN", Scrollable.of(PersonSummary_.id, 10))
        }
        capture.printStatements("searchByName")
        assertEquals(1, capture.count(Operation.SELECT))
        val statement = capture.statements().single().statement()
        // The projection narrows the SELECT: no birth/death year columns.
        assertFalse(statement.contains("birth_year"))
        assertFalse(statement.contains("death_year"))
        assertEquals(listOf("Morgan Freeman"), window.content().map { it.primaryName })
    }

    @Test
    fun `findNameSuggestions orders by name`(orm: ORMTemplate, capture: SqlCapture) {
        val personSummaryRepository = orm.repository<PersonSummaryRepository>()
        val suggestions = capture.execute {
            personSummaryRepository.findNameSuggestions("an", 10)
        }
        capture.printStatements("findNameSuggestions")
        assertEquals(listOf("Keanu Reeves", "Morgan Freeman"), suggestions.map { it.primaryName })
    }
}
