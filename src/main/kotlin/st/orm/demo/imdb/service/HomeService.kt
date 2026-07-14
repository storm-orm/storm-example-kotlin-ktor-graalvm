package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.GenreMovieCount
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.ref
import st.orm.template.transaction

/** The featured hero section: the most recently viewed movie with context. */
data class FeaturedMovie(
    val movie: Movie,
    val rating: Rating?,
    val genres: List<Genre>
)

/** Everything the home page shows. */
data class HomeView(
    val featured: FeaturedMovie?,
    val recentlyViewed: List<Movie>,
    val topRated: List<Rating>,
    val genreCounts: List<GenreMovieCount>,
    val watchlistEntries: List<Watchlist>
)

class HomeService(
    private val movieRepository: MovieRepository,
    private val movieViewRepository: MovieViewRepository,
    private val ratingRepository: RatingRepository,
    private val genreRepository: GenreRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val watchlistRepository: WatchlistRepository
) {

    /** All home page sections, read in one read-only transaction. */
    suspend fun buildHomeView(): HomeView = transaction(readOnly = true) {
        // Views hold movie refs; dedupe the refs and fetch the movies once.
        val recentMovieRefs = movieViewRepository.findRecentViews(RECENT_VIEW_SAMPLE_SIZE)
            .map { it.movie }
            .distinct()
            .take(RECENTLY_VIEWED_LIMIT + 1)
        val moviesByRef = movieRepository.findAllByRef(recentMovieRefs).associateBy { it.ref() }
        val recentMovies = recentMovieRefs.mapNotNull { moviesByRef[it] }

        val featured = recentMovies.firstOrNull()?.let { movie ->
            FeaturedMovie(
                movie = movie,
                rating = ratingRepository.findById(movie),
                genres = movieGenreRepository.findGenres(movie)
            )
        }
        HomeView(
            featured = featured,
            recentlyViewed = recentMovies.drop(1).take(RECENTLY_VIEWED_LIMIT),
            topRated = ratingRepository.findTopRated(TOP_RATED_MINIMUM_VOTES, TOP_RATED_LIMIT),
            genreCounts = genreRepository.findGenresWithMovieCounts(),
            watchlistEntries = watchlistRepository.findMostRecent(WATCHLIST_PREVIEW_LIMIT)
        )
    }

    companion object {
        private const val RECENT_VIEW_SAMPLE_SIZE = 50
        private const val RECENTLY_VIEWED_LIMIT = 12
        private const val TOP_RATED_LIMIT = 10
        private const val TOP_RATED_MINIMUM_VOTES = 25_000
        private const val WATCHLIST_PREVIEW_LIMIT = 12
    }
}
