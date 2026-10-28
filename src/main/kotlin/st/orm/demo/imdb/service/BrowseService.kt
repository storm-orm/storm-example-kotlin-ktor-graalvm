package st.orm.demo.imdb.service

import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.repository.GenreMovieCount
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.TopMoviesSort
import st.orm.template.transaction

/** The orders the browse page lists in: the best rated, the newest among the well voted, or everything, the most voted first. */
enum class BrowseSort(val key: String) {
    RATING("rating"),
    YEAR("year"),
    VOTES("votes");

    companion object {
        fun of(key: String?): BrowseSort = entries.firstOrNull { it.key == key } ?: RATING
    }
}

/**
 * Everything the browse page shows: the genres with their counts to choose
 * from, the chosen one with how many movies it has, and the list: the fifty
 * best rated or newest with at least 25,000 votes ([ranked]), or the first
 * window of all of them, the most voted first ([window]), which scrolls.
 */
data class BrowseView(
    val genres: List<GenreMovieCount>,
    val genre: Genre?,
    val movieCount: Long?,
    val sort: BrowseSort,
    val ranked: List<Rating>,
    val window: Window<Rating>?
)

/**
 * Browsing the catalogue: by genre or all of it, and in one of three
 * orders, among the movies the visitor's filter lets through. The ranked
 * lists are one bounded query each; all of a genre is a keyset scroll.
 */
class BrowseService(
    private val genreRepository: GenreRepository,
    private val movieRepository: MovieRepository,
    private val ratingRepository: RatingRepository,
    private val statisticsService: StatisticsService
) {

    /** The browse page, or null for a genre that does not exist. */
    suspend fun browse(genreName: String?, sort: BrowseSort, filter: MovieFilter?): BrowseView? = transaction(readOnly = true) {
        val genre = genreName?.let { genreRepository.findByName(it) ?: return@transaction null }
        BrowseView(
            genres = statisticsService.genreCounts(),
            genre = genre,
            movieCount = genre?.let { movieRepository.countByGenre(it, filter) },
            sort = sort,
            ranked = when (sort) {
                BrowseSort.RATING -> ratingRepository.findTopMovies(genre, TopMoviesSort.RATING, filter, MINIMUM_VOTE_COUNT, RANKED_LIMIT)
                BrowseSort.YEAR -> ratingRepository.findTopMovies(genre, TopMoviesSort.YEAR, filter, MINIMUM_VOTE_COUNT, RANKED_LIMIT)
                BrowseSort.VOTES -> emptyList()
            },
            window = if (sort == BrowseSort.VOTES) ratingRepository.scrollMostVoted(genre, null, filter, RatingRepository.mostVotedFirst(PAGE_SIZE)) else null
        )
    }

    /**
     * The next window of all the movies, of a genre or of every one, the
     * most voted first; null for a genre that does not exist. The cursor is
     * the opaque string from the previous response, which the client echoes
     * back unchanged.
     */
    suspend fun scroll(genreName: String?, filter: MovieFilter?, cursor: String): Window<Rating>? = transaction(readOnly = true) {
        val genre = genreName?.let { genreRepository.findByName(it) ?: return@transaction null }
        ratingRepository.scrollMostVoted(genre, null, filter, RatingRepository.mostVotedFirst(PAGE_SIZE).from(cursor))
    }

    companion object {
        private const val MINIMUM_VOTE_COUNT = 25_000
        private const val RANKED_LIMIT = 50
        private const val PAGE_SIZE = 24
    }
}
