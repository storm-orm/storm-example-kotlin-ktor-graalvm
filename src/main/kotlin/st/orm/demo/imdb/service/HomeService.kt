package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.GenreMovieCount
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.ref
import st.orm.template.transaction

/** The featured hero section: the site's most recently viewed movie with context. */
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
    /** The visitor's recently viewed (above), watchlist, seen list and recommendations; the other sections are the site's. */
    val watchlistEntries: List<Watchlist>,
    val seenEntries: List<MovieSeen>,
    val recommendations: HomeRecommendations,
    /** What the visitor's friends saw lately, as far as they share it. */
    val friendsSeen: List<FriendSeenMovie>
)

class HomeService(
    private val movieRepository: MovieRepository,
    private val movieViewRepository: MovieViewRepository,
    private val ratingRepository: RatingRepository,
    private val statisticsService: StatisticsService,
    private val movieGenreRepository: MovieGenreRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val recommendationService: RecommendationService,
    private val friendService: FriendService
) {

    /** All home page sections for the visitor, read in one read-only transaction. */
    suspend fun buildHomeView(user: User): HomeView = transaction(readOnly = true) {
        HomeView(
            featured = featured(),
            recentlyViewed = recentlyViewed(user),
            topRated = ratingRepository.findTopRated(TOP_RATED_MINIMUM_VOTES, TOP_RATED_LIMIT),
            genreCounts = statisticsService.genreCounts(),
            watchlistEntries = watchlistRepository.findMostRecent(user, LIST_PREVIEW_LIMIT),
            seenEntries = movieSeenRepository.findMostRecent(user, LIST_PREVIEW_LIMIT),
            recommendations = recommendationService.forHome(user),
            friendsSeen = friendService.latestOfFriends(user, LIST_PREVIEW_LIMIT)
        )
    }

    /** The site's latest view, with its rating and genres: the hero at the top of the page. */
    private fun featured(): FeaturedMovie? {
        val movie = movieViewRepository.findRecentViews(1).firstOrNull()?.movie?.let { movieRepository.findByRef(it) } ?: return null
        return FeaturedMovie(movie = movie, rating = ratingRepository.findById(movie), genres = movieGenreRepository.findGenres(movie))
    }

    /** The movies the visitor viewed last, each once. Views hold movie refs; the movies are fetched in one query. */
    private fun recentlyViewed(user: User): List<Movie> {
        val refs = movieViewRepository.findRecentViews(user, RECENT_VIEW_SAMPLE_SIZE)
            .map { it.movie }
            .distinct()
            .take(RECENTLY_VIEWED_LIMIT)
        val moviesByRef = movieRepository.findAllByRef(refs).associateBy { it.ref() }
        return refs.mapNotNull { moviesByRef[it] }
    }

    companion object {
        private const val RECENT_VIEW_SAMPLE_SIZE = 50
        private const val RECENTLY_VIEWED_LIMIT = 12
        private const val TOP_RATED_LIMIT = 10
        private const val TOP_RATED_MINIMUM_VOTES = 25_000
        private const val LIST_PREVIEW_LIMIT = 12
    }
}
