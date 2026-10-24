package st.orm.demo.imdb.service

import st.orm.Page
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.RecommendationEventKind
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.model.WatchlistPk
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import java.time.Instant

class WatchlistService(
    private val movieRepository: MovieRepository,
    private val userRepository: UserRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val recommendationService: RecommendationService
) {

    /**
     * Adds the movie to the user's watchlist, or removes it when already
     * present, and returns the state of both lists afterwards. A movie
     * saved from a recommendation counts as one that led somewhere, and
     * either way the user's recommendations are recomputed in the background.
     *
     * The exists/insert/remove cycle must be atomic per request, and the
     * boundary lives in the routing declaration: the toggle route is wrapped
     * in `transactional { }` (see movieRoutes), which opens a Storm
     * transaction around the handler. The service stays transaction-free;
     * called from another transaction it simply joins it (REQUIRED).
     */
    suspend fun toggle(user: User, movieId: String): MovieListState {
        val movie = movieRepository.getById(movieId)
        val now = Instant.now()
        val stored = userRepository.touch(user, now)
        val onWatchlist = if (watchlistRepository.contains(stored, movie)) {
            watchlistRepository.removeById(WatchlistPk(userId = stored.id, movieId = movie.id))
            false
        } else {
            watchlistRepository.insert(Watchlist(user = stored, movie = movie, addedAt = now))
            recommendationService.record(stored, movie, RecommendationEventKind.SAVED)
            true
        }
        recommendationService.refreshLater(stored)
        val seen = movieSeenRepository.findById(MovieSeenPk(userId = stored.id, movieId = movie.id))
        return MovieListState.of(onWatchlist = onWatchlist, seen = seen)
    }

    /** One page of the user's watchlist for the watchlist page (0-based). */
    fun findPage(user: User, pageNumber: Int): Page<Watchlist> =
        watchlistRepository.findPage(user, pageNumber, WATCHLIST_PAGE_SIZE)

    companion object {
        private const val WATCHLIST_PAGE_SIZE = 12
    }
}
