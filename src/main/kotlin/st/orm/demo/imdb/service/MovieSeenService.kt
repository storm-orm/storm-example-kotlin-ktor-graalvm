package st.orm.demo.imdb.service

import st.orm.Page
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import java.time.Instant

class MovieSeenService(
    private val movieRepository: MovieRepository,
    private val userRepository: UserRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository
) {

    /**
     * Marks the movie as seen by the user, or unmarks it when already seen,
     * and returns the state of both lists afterwards. A movie just seen
     * comes off the watchlist: that is what a list of movies to watch means.
     * Unmarking does not put it back.
     *
     * Like the watchlist toggle, the cycle runs in the route's
     * `transactional { }` block, so the mark and the watchlist removal are
     * one transaction without transaction code here.
     */
    suspend fun toggle(user: User, movieId: String): MovieListState {
        val movie = movieRepository.getById(movieId)
        val now = Instant.now()
        val stored = userRepository.touch(user, now)
        return if (movieSeenRepository.contains(stored, movie)) {
            movieSeenRepository.removeById(MovieSeenPk(userId = stored.id, movieId = movie.id))
            MovieListState(onWatchlist = watchlistRepository.contains(stored, movie), seen = false)
        } else {
            movieSeenRepository.insert(MovieSeen(user = stored, movie = movie, seenAt = now))
            watchlistRepository.remove(stored, movie)
            MovieListState(onWatchlist = false, seen = true)
        }
    }

    /** One page of the movies the user has seen, for the seen page (0-based). */
    fun findPage(user: User, pageNumber: Int): Page<MovieSeen> =
        movieSeenRepository.findPage(user, pageNumber, SEEN_PAGE_SIZE)

    companion object {
        private const val SEEN_PAGE_SIZE = 12
    }
}
