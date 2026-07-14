package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.TopMoviesSort
import st.orm.template.transaction

/** Everything the top movies page shows. */
data class TopMoviesView(
    val genres: List<Genre>,
    val selectedGenre: Genre?,
    val entries: List<Rating>
)

class TopMoviesService(
    private val genreRepository: GenreRepository,
    private val ratingRepository: RatingRepository
) {

    /** The top movies page: filter options and entries in one read-only transaction. */
    suspend fun findTopMovies(genreName: String?, sortBy: TopMoviesSort): TopMoviesView =
        transaction(readOnly = true) {
            val selectedGenre = genreName?.let { genreRepository.findByName(it) }
            TopMoviesView(
                genres = genreRepository.findAllOrderedByName(),
                selectedGenre = selectedGenre,
                entries = ratingRepository.findTopMovies(
                    selectedGenre, sortBy, MINIMUM_VOTE_COUNT, TOP_MOVIES_LIMIT)
            )
        }

    companion object {
        private const val MINIMUM_VOTE_COUNT = 25_000
        private const val TOP_MOVIES_LIMIT = 50
    }
}
