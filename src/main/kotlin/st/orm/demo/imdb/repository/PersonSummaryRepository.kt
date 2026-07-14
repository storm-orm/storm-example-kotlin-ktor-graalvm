package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.PersonSummary
import st.orm.demo.imdb.model.PersonSummary_
import st.orm.repository.ProjectionRepository

interface PersonSummaryRepository : ProjectionRepository<PersonSummary, String> {

    /** Case-insensitive name search with keyset scrolling. */
    fun searchByName(query: String, scrollable: Scrollable<PersonSummary>): Window<PersonSummary> {
        val pattern = "%$query%"
        return select()
            .where { "LOWER(${PersonSummary_.primaryName}) LIKE LOWER($pattern)" }
            .scroll(scrollable)
    }

    /** Name suggestions for the search auto-complete. */
    fun findNameSuggestions(query: String, limit: Int): List<PersonSummary> {
        val pattern = "%$query%"
        return select()
            .where { "LOWER(${PersonSummary_.primaryName}) LIKE LOWER($pattern)" }
            .orderBy(PersonSummary_.primaryName)
            .limit(limit)
            .resultList
    }
}
