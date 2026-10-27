package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Person_
import st.orm.repository.EntityRepository
import st.orm.template.eqIgnoreCase
import st.orm.template.search
import st.orm.template.startsWithIgnoreCase
import st.orm.template.suggest

/**
 * Person lookups for the detail page use the inherited CRUD methods
 * (getById, findAllByRef); search and suggestions are queries of their own.
 */
interface PersonRepository : EntityRepository<Person, String> {

    /**
     * Name search with keyset scrolling. Every word of the query must occur
     * in the name, ignoring case and accents, so "reeves keanu" finds Keanu
     * Reeves and "penelope cruz" finds Penélope Cruz. The database removes the
     * accents from the names and the query alike ([folded]), through the
     * expression its trigram index is built on. A name equal to the query
     * comes first and a name starting with it next; scroll follows the ranks.
     */
    fun searchByName(query: String, scrollable: Scrollable<Person>): Window<Person> =
        select()
            .where(search(query, Person_.primaryName.folded))
            .orderFirst(Person_.primaryName.folded eqIgnoreCase query)
            .orderFirst(Person_.primaryName.folded startsWithIgnoreCase query)
            .scroll(scrollable)

    /**
     * Name suggestions for the search auto-complete. Like the movie
     * suggestions it uses suggest, which ignores words the trigram index
     * cannot serve, so every keystroke reads the index. They are ranked as
     * [searchByName] ranks them, alphabetically within each rank; a game's
     * box ranks the first of them by how well known they are (SearchService).
     */
    fun findNameSuggestions(query: String, limit: Int): List<Person> =
        select()
            .where(suggest(query, Person_.primaryName.folded))
            .orderFirst(Person_.primaryName.folded eqIgnoreCase query)
            .orderFirst(Person_.primaryName.folded startsWithIgnoreCase query)
            .orderBy(Person_.primaryName)
            .limit(limit)
            .resultList
}
