package st.orm.demo.imdb.service

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Person_
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction

/** The first result windows of both search sections. */
data class SearchResults(
    val movieWindow: Window<Movie>,
    val personWindow: Window<Person>
)

/** Auto-complete suggestions for both types. */
data class Suggestions(
    val movies: List<Movie>,
    val persons: List<Person>
)

class SearchService(
    private val movieRepository: MovieRepository,
    private val personRepository: PersonRepository,
    private val principalRepository: PrincipalRepository
) {

    /** The search page: both first windows in one read-only transaction. */
    suspend fun search(query: String): SearchResults = transaction(readOnly = true) {
        SearchResults(
            movieWindow = movieRepository.searchMovies(
                query, MovieRepository.mostVotedFirst(MOVIE_PAGE_SIZE)),
            personWindow = personRepository.searchByName(
                query, Scrollable.of(Person_.id, PERSON_PAGE_SIZE))
        )
    }

    /**
     * The next window of movie results. The cursor is the opaque string
     * from the previous response: null requests the first window, any other
     * value resumes exactly where the client left off. Purely server-side
     * navigation would use window.next() instead — no cursor involved.
     */
    fun scrollMovies(query: String, cursor: String?): Window<Movie> {
        val scrollable = if (cursor != null) {
            MovieRepository.mostVotedFirst(MOVIE_PAGE_SIZE).from(cursor)
        } else {
            MovieRepository.mostVotedFirst(MOVIE_PAGE_SIZE)
        }
        return movieRepository.searchMovies(query, scrollable)
    }

    /** The next window of person results — same cursor contract as [scrollMovies]. */
    fun scrollPersons(query: String, cursor: String?): Window<Person> {
        val scrollable = if (cursor != null) {
            Scrollable.of(Person_.id, PERSON_PAGE_SIZE).from(cursor)
        } else {
            Scrollable.of(Person_.id, PERSON_PAGE_SIZE)
        }
        return personRepository.searchByName(query, scrollable)
    }

    /**
     * Auto-complete: movie and person suggestions. The box queries on every
     * keystroke, so the two queries run concurrently, each in a read-only
     * transaction of its own on a connection of its own, and the response
     * waits for the slower one instead of for both in turn. The two lists
     * need no common snapshot.
     */
    /** Movies by their titles alone, for the games' boxes: no movie suggested for its cast. */
    /**
     * People by their names alone, for a game's box: of the first few dozen
     * that match, the best known first, so "keanu" finds Keanu Reeves before
     * the other Keanus; a name matched in full or at its start still first.
     */
    suspend fun findNameSuggestions(query: String): List<Person> = transaction(readOnly = true) {
        val candidates = personRepository.findNameSuggestions(query, NAME_CANDIDATES)
        val credits = principalRepository.countWellKnownCredits(candidates.map { it.ref() }, WELL_KNOWN_VOTES).associate { it.person.entityId() to it.credits }
        val folded = query.trim().lowercase()
        candidates.sortedWith(
            compareByDescending<Person> { it.primaryName.lowercase() == folded }
                .thenByDescending { it.primaryName.lowercase().startsWith(folded) }
                .thenByDescending { credits[it.id] ?: 0L }
                .thenBy { it.primaryName }
        ).take(NAME_SUGGESTION_LIMIT)
    }

    suspend fun findTitleSuggestions(query: String): List<Movie> = transaction(readOnly = true) {
        movieRepository.findTitleSuggestions(query, MOVIE_SUGGESTION_LIMIT)
    }

    suspend fun findSuggestions(query: String): Suggestions = coroutineScope {
        val movies = async {
            transaction(readOnly = true) {
                movieRepository.findMovieSuggestions(query, MOVIE_SUGGESTION_LIMIT)
            }
        }
        val persons = async {
            transaction(readOnly = true) {
                personRepository.findNameSuggestions(query, PERSON_SUGGESTION_LIMIT)
            }
        }
        Suggestions(movies = movies.await(), persons = persons.await())
    }

    companion object {
        private const val MOVIE_PAGE_SIZE = 18
        private const val PERSON_PAGE_SIZE = 12
        private const val MOVIE_SUGGESTION_LIMIT = 6
        private const val PERSON_SUGGESTION_LIMIT = 4
        /** The names a game's box suggests: more than the search box, as a name is all there is to go on. */
        private const val NAME_SUGGESTION_LIMIT = 8
        /** The names looked at before the best known are picked out. */
        private const val NAME_CANDIDATES = 40
        /** The votes a movie needs for a credit in it to count towards how well known a person is. */
        private const val WELL_KNOWN_VOTES = 50_000
    }
}
