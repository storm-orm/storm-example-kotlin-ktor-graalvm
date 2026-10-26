package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.model.MovieSeen_
import st.orm.template.eq
import st.orm.template.ref
import st.orm.template.transaction

/** The featured hero section: a pick from the visitor's For You list, with its rating and genres. */
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
    /** The visitor's recently viewed (above), watchlist, seen list and recommendations; the other sections are the site's. */
    val watchlistEntries: List<Watchlist>,
    val seenEntries: List<MovieSeen>,
    val recommendations: HomeRecommendations,
    /** What the visitor's friends saw lately, as far as they share it. */
    val friendsSeen: List<SharedCheckIn>,
    /** What the other members of the visitor's clubs saw lately, as far as they share it. */
    val clubsSeen: List<SharedCheckIn>,
    /**
     * Whether the visitor has friends or is in a club: then the page is
     * about them, with longer rows of what they saw, and without the seen
     * list, the recently viewed and the top ten, which are not read at all.
     */
    val community: Boolean,
    /** How many movies the visitor has marked watched: under ten, the page points them to the catch-up page. */
    val seenCount: Long
)

class HomeService(
    private val movieRepository: MovieRepository,
    private val movieViewRepository: MovieViewRepository,
    private val ratingRepository: RatingRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val recommendationService: RecommendationService,
    private val friendService: FriendService,
    private val clubService: ClubService
) {

    /**
     * All home page sections for the visitor, read in one read-only
     * transaction. A visitor with friends or clubs gets the community's rows
     * at twice the length, and not the seen list, the recently viewed or the
     * top ten, which are then not read. The recommendations come first, on
     * their own: they count the picks shown in a write after their read, which
     * must not wait for a connection while this transaction holds one.
     */
    suspend fun buildHomeView(user: User): HomeView {
        val recommendations = recommendationService.forHome(user)
        return transaction(readOnly = true) { homeView(user, recommendations) }
    }

    private suspend fun homeView(user: User, recommendations: HomeRecommendations): HomeView {
        val community = user.name != null && (friendService.hasFriends(user) || clubService.isMember(user))
        val feedLimit = if (community) COMMUNITY_ROW_LIMIT else LIST_PREVIEW_LIMIT
        return HomeView(
            featured = recommendations.featured?.let { featured(it) },
            recentlyViewed = if (community) emptyList() else recentlyViewed(user),
            topRated = if (community) emptyList() else ratingRepository.findTopRated(TOP_RATED_MINIMUM_VOTES, user.movieFilter, TOP_RATED_LIMIT),
            watchlistEntries = watchlistRepository.findMostRecent(user, LIST_PREVIEW_LIMIT),
            seenEntries = if (community) emptyList() else movieSeenRepository.findMostRecent(user, LIST_PREVIEW_LIMIT),
            recommendations = recommendations,
            friendsSeen = friendService.latestOfFriends(user, feedLimit),
            clubsSeen = if (community) clubService.latestInClubs(user, feedLimit) else emptyList(),
            community = community,
            seenCount = movieSeenRepository.count(MovieSeen_.user eq user)
        )
    }

    /** The featured pick with its rating and genres: the hero at the top of the page. */
    private fun featured(pick: RecommendedMovie) =
        FeaturedMovie(movie = pick.movie, rating = ratingRepository.findById(pick.movie), genres = movieGenreRepository.findGenres(pick.movie))

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
        private const val COMMUNITY_ROW_LIMIT = 24
    }
}
