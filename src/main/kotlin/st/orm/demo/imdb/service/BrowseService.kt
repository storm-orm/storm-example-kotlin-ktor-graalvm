package st.orm.demo.imdb.service

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.MovieSummary
import st.orm.demo.imdb.model.MovieSummary_
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieSummaryRepository
import st.orm.template.transaction

/** Everything the browse-by-genre page shows. */
data class BrowseView(
    val genre: Genre,
    val movieCount: Long,
    val movieWindow: Window<MovieSummary>
)

class BrowseService(
    private val genreRepository: GenreRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val movieSummaryRepository: MovieSummaryRepository
) {

    /** The browse page: genre, count, and first window in one read-only transaction. */
    suspend fun browseGenre(genreName: String): BrowseView? = transaction(readOnly = true) {
        val genre = genreRepository.findByName(genreName) ?: return@transaction null
        BrowseView(
            genre = genre,
            movieCount = movieGenreRepository.countBy(MovieGenre_.genre, genre),
            movieWindow = movieSummaryRepository.scrollByGenre(
                genre, Scrollable.of(MovieSummary_.id, PAGE_SIZE))
        )
    }

    /**
     * The next keyset window of a genre, or null for an unknown genre. The
     * cursor is the opaque string from the previous response (null requests
     * the first window) — the client echoes it back unchanged.
     */
    suspend fun scrollGenre(genreName: String, cursor: String?): Window<MovieSummary>? =
        transaction(readOnly = true) {
            val genre = genreRepository.findByName(genreName) ?: return@transaction null
            val scrollable = if (cursor != null) {
                Scrollable.fromCursor(MovieSummary_.id, cursor)
            } else {
                Scrollable.of(MovieSummary_.id, PAGE_SIZE)
            }
            movieSummaryRepository.scrollByGenre(genre, scrollable)
        }

    companion object {
        private const val PAGE_SIZE = 24
    }
}
