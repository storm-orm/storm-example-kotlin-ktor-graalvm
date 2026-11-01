package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.PersonSummary
import st.orm.demo.imdb.model.PersonSummary_
import st.orm.repository.ProjectionRepository
import st.orm.template.eqIgnoreCase
import st.orm.template.search
import st.orm.template.startsWithIgnoreCase
import st.orm.template.suggest

interface PersonSummaryRepository : ProjectionRepository<PersonSummary, String> {

    /**
     * Name search with keyset scrolling. Every word of the query must occur
     * in the name, ignoring case and accents, so "reeves keanu" finds Keanu
     * Reeves and "penelope cruz" finds Penélope Cruz. The database removes the
     * accents from the names and the query alike ([folded]), through the
     * expression its trigram index is built on. A name equal to the query
     * comes first and a name starting with it next; scroll follows the ranks.
     */
    fun searchByName(query: String, scrollable: Scrollable<PersonSummary>): Window<PersonSummary> {
        val name = searchText(query)
        return select()
            .where(search(name, PersonSummary_.primaryName.folded))
            .orderFirst(PersonSummary_.primaryName.folded eqIgnoreCase name)
            .orderFirst(PersonSummary_.primaryName.folded startsWithIgnoreCase name)
            .scroll(scrollable)
    }

    /**
     * Name suggestions for the search auto-complete. Like the movie
     * suggestions it uses suggest, which ignores words the trigram index
     * cannot serve, so every keystroke reads the index. They are ranked as
     * [searchByName] ranks them, alphabetically within each rank.
     */
    fun findNameSuggestions(query: String, limit: Int): List<PersonSummary> {
        val name = searchText(query)
        return select()
            .where(suggest(name, PersonSummary_.primaryName.folded))
            .orderFirst(PersonSummary_.primaryName.folded eqIgnoreCase name)
            .orderFirst(PersonSummary_.primaryName.folded startsWithIgnoreCase name)
            .orderBy(PersonSummary_.primaryName)
            .limit(limit)
            .resultList
    }
}
