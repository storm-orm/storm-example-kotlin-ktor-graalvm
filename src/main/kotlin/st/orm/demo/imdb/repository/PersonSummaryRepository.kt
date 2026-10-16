package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.PersonSummary
import st.orm.demo.imdb.model.PersonSummary_
import st.orm.repository.ProjectionRepository
import st.orm.template.search

interface PersonSummaryRepository : ProjectionRepository<PersonSummary, String> {

    /**
     * Name search with keyset scrolling. Every word of the query must occur
     * in the name, ignoring case, so "reeves keanu" finds Keanu Reeves.
     */
    fun searchByName(query: String, scrollable: Scrollable<PersonSummary>): Window<PersonSummary> =
        select()
            .where(search(query, PersonSummary_.primaryName))
            .scroll(scrollable)

    /** Name suggestions for the search auto-complete. */
    fun findNameSuggestions(query: String, limit: Int): List<PersonSummary> =
        select()
            .where(search(query, PersonSummary_.primaryName))
            .orderBy(PersonSummary_.primaryName)
            .limit(limit)
            .resultList
}
