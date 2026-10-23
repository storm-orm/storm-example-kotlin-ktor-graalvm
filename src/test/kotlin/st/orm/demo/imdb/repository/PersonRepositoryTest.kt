package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import st.orm.Scrollable
import st.orm.demo.imdb.model.Person_
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class PersonRepositoryTest {

    @Test
    fun `searchByName finds a name ignoring case`(orm: ORMTemplate, capture: SqlCapture) {
        val personRepository = orm.repository<PersonRepository>()
        val window = capture.execute {
            personRepository.searchByName("FREEMAN", Scrollable.of(Person_.id, 10))
        }
        capture.printStatements("searchByName")
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals(listOf("Morgan Freeman"), window.content().map { it.primaryName })
    }

    @Test
    fun `findNameSuggestions orders by name`(orm: ORMTemplate, capture: SqlCapture) {
        val personRepository = orm.repository<PersonRepository>()
        val suggestions = capture.execute {
            personRepository.findNameSuggestions("an", 10)
        }
        capture.printStatements("findNameSuggestions")
        assertEquals(listOf("Keanu Reeves", "Morgan Freeman"), suggestions.map { it.primaryName })
    }

    @Test
    fun `searchByName matches every word of the query in any order`(orm: ORMTemplate) {
        val personRepository = orm.repository<PersonRepository>()
        fun names(query: String) =
            personRepository.searchByName(query, Scrollable.of(Person_.id, 10))
                .content().map { it.primaryName }
        assertEquals(listOf("Keanu Reeves"), names("reeves keanu"))
        assertEquals(emptyList<String>(), names("keanu freeman"))
    }
}
