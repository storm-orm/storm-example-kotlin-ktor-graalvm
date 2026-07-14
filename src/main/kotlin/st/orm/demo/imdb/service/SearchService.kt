package st.orm.demo.imdb.service

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.MovieSummary
import st.orm.demo.imdb.model.MovieSummary_
import st.orm.demo.imdb.model.PersonSummary
import st.orm.demo.imdb.model.PersonSummary_
import st.orm.demo.imdb.repository.MovieSummaryRepository
import st.orm.demo.imdb.repository.PersonSummaryRepository
import st.orm.template.transaction

/** The first result windows of both search sections. */
data class SearchResults(
    val movieWindow: Window<MovieSummary>,
    val personWindow: Window<PersonSummary>
)

/** Auto-complete suggestions for both types. */
data class Suggestions(
    val movies: List<MovieSummary>,
    val persons: List<PersonSummary>
)

class SearchService(
    private val movieSummaryRepository: MovieSummaryRepository,
    private val personSummaryRepository: PersonSummaryRepository
) {

    /** The search page: both first windows in one read-only transaction. */
    suspend fun search(query: String): SearchResults = transaction(readOnly = true) {
        SearchResults(
            movieWindow = movieSummaryRepository.searchByTitle(
                query, Scrollable.of(MovieSummary_.id, MOVIE_PAGE_SIZE)),
            personWindow = personSummaryRepository.searchByName(
                query, Scrollable.of(PersonSummary_.id, PERSON_PAGE_SIZE))
        )
    }

    /**
     * The next window of movie results. The cursor is the opaque string
     * from the previous response: null requests the first window, any other
     * value resumes exactly where the client left off. Purely server-side
     * navigation would use window.next() instead — no cursor involved.
     */
    fun scrollMovies(query: String, cursor: String?): Window<MovieSummary> {
        val scrollable = if (cursor != null) {
            Scrollable.fromCursor(MovieSummary_.id, cursor)
        } else {
            Scrollable.of(MovieSummary_.id, MOVIE_PAGE_SIZE)
        }
        return movieSummaryRepository.searchByTitle(query, scrollable)
    }

    /** The next window of person results — same cursor contract as [scrollMovies]. */
    fun scrollPersons(query: String, cursor: String?): Window<PersonSummary> {
        val scrollable = if (cursor != null) {
            Scrollable.fromCursor(PersonSummary_.id, cursor)
        } else {
            Scrollable.of(PersonSummary_.id, PERSON_PAGE_SIZE)
        }
        return personSummaryRepository.searchByName(query, scrollable)
    }

    /** Auto-complete: movie and person suggestions in one read-only transaction. */
    suspend fun findSuggestions(query: String): Suggestions = transaction(readOnly = true) {
        Suggestions(
            movies = movieSummaryRepository.findTitleSuggestions(query, MOVIE_SUGGESTION_LIMIT),
            persons = personSummaryRepository.findNameSuggestions(query, PERSON_SUGGESTION_LIMIT)
        )
    }

    companion object {
        private const val MOVIE_PAGE_SIZE = 18
        private const val PERSON_PAGE_SIZE = 12
        private const val MOVIE_SUGGESTION_LIMIT = 6
        private const val PERSON_SUGGESTION_LIMIT = 4
    }
}
