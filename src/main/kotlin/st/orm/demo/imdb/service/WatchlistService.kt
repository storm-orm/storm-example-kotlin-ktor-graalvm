package st.orm.demo.imdb.service

import st.orm.Page
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import java.time.Instant

class WatchlistService(
    private val movieRepository: MovieRepository,
    private val watchlistRepository: WatchlistRepository
) {

    /**
     * Adds the movie to the watchlist, or removes it when already present.
     * Returns whether the movie is on the watchlist afterwards.
     *
     * The exists/insert/remove cycle must be atomic per request, and the
     * boundary lives in the routing declaration: the toggle route is wrapped
     * in `transactional { }` (see movieRoutes), which opens a Storm
     * transaction around the handler. The service stays transaction-free;
     * called from another transaction it simply joins it (REQUIRED).
     */
    suspend fun toggle(movieId: String): Boolean {
        val movie = movieRepository.getById(movieId)
        return if (watchlistRepository.existsById(movie)) {
            watchlistRepository.removeById(movie)
            false
        } else {
            watchlistRepository.insert(Watchlist(movie = movie, addedAt = Instant.now()))
            true
        }
    }

    /** One page of the watchlist for the watchlist page (0-based). */
    fun findPage(pageNumber: Int): Page<Watchlist> =
        watchlistRepository.findPage(pageNumber, WATCHLIST_PAGE_SIZE)

    companion object {
        private const val WATCHLIST_PAGE_SIZE = 12
    }
}
