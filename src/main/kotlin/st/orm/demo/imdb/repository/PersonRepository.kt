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
 * (getById, findAllByRef); search and suggestions are the queries below.
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
    fun searchByName(query: String, scrollable: Scrollable<Person>): Window<Person> {
        val name = searchText(query)
        return select()
            .where(search(name, Person_.primaryName.folded))
            .orderFirst(Person_.primaryName.folded eqIgnoreCase name)
            .orderFirst(Person_.primaryName.folded startsWithIgnoreCase name)
            .scroll(scrollable)
    }

    /**
     * Name suggestions for the search auto-complete. Like the movie
     * suggestions it uses suggest, so every keystroke reads the trigram
     * index of V1: a word of three letters or more matched anywhere in a
     * name and a word of two at the start of one, so in "de niro" "niro"
     * matches anywhere and "de" at the start of a word. A word of one letter
     * takes no part, so an input of such words alone suggests nothing. No
     * words are ignored: the particles of names, such as "de" and "van",
     * are what the user types to find De Niro or Van Damme. They are ranked
     * as [searchByName] ranks them, alphabetically within each rank.
     */
    fun findNameSuggestions(query: String, limit: Int): List<Person> {
        val name = searchText(query)
        return select()
            .where(suggest(name, Person_.primaryName.folded))
            .orderFirst(Person_.primaryName.folded eqIgnoreCase name)
            .orderFirst(Person_.primaryName.folded startsWithIgnoreCase name)
            .orderBy(Person_.primaryName)
            .limit(limit)
            .resultList
    }
}
