package st.orm.demo.imdb.service

import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.template.transaction

/** Everything the browse-by-genre page shows. */
data class BrowseView(
    val genre: Genre,
    val movieCount: Long,
    val movieWindow: Window<Movie>
)

class BrowseService(
    private val genreRepository: GenreRepository,
    private val movieRepository: MovieRepository
) {

    /**
     * The browse page: genre, count, and first window in one read-only
     * transaction, among the movies [filter] lets through. The genre lists
     * its most voted movies first.
     */
    suspend fun browseGenre(genreName: String, filter: MovieFilter?): BrowseView? = transaction(readOnly = true) {
        val genre = genreRepository.findByName(genreName) ?: return@transaction null
        BrowseView(
            genre = genre,
            movieCount = movieRepository.countByGenre(genre, filter),
            movieWindow = movieRepository.scrollByGenre(
                genre, filter, MovieRepository.mostVotedFirst(PAGE_SIZE))
        )
    }

    /**
     * The next keyset window of a genre, or null for an unknown genre. The
     * cursor is the opaque string from the previous response (null requests
     * the first window) — the client echoes it back unchanged.
     */
    suspend fun scrollGenre(genreName: String, filter: MovieFilter?, cursor: String?): Window<Movie>? =
        transaction(readOnly = true) {
            val genre = genreRepository.findByName(genreName) ?: return@transaction null
            val scrollable = if (cursor != null) {
                MovieRepository.mostVotedFirst(PAGE_SIZE).from(cursor)
            } else {
                MovieRepository.mostVotedFirst(PAGE_SIZE)
            }
            movieRepository.scrollByGenre(genre, filter, scrollable)
        }

    companion object {
        private const val PAGE_SIZE = 24
    }
}
