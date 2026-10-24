package st.orm.demo.imdb.service

import st.orm.Page
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.RecommendationEventKind
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.transaction
import java.time.Instant
import java.time.ZoneOffset

/**
 * What an IMDb import did: the movies it marked as seen, the ones already
 * seen that took the date from IMDb, and the titles the catalogue does not
 * hold (series, episodes, and movies with too few votes).
 */
data class ImdbImport(val added: Int, val updated: Int, val skipped: Int)

class MovieSeenService(
    private val movieRepository: MovieRepository,
    private val userRepository: UserRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val recommendationService: RecommendationService
) {

    /**
     * Marks the movie as seen by the user, or unmarks it when already seen,
     * and returns the state of both lists afterwards. A movie just seen
     * comes off the watchlist: that is what a list of movies to watch means.
     * Unmarking does not put it back. Either way the user's recommendations
     * are recomputed in the background.
     *
     * Like the watchlist toggle, the cycle runs in the route's
     * `transactional { }` block, so the mark and the watchlist removal are
     * one transaction without transaction code here.
     */
    suspend fun toggle(user: User, movieId: String): MovieListState {
        val movie = movieRepository.getById(movieId)
        val now = Instant.now()
        val stored = userRepository.touch(user, now)
        val state = if (movieSeenRepository.contains(stored, movie)) {
            movieSeenRepository.removeById(MovieSeenPk(userId = stored.id, movieId = movie.id))
            MovieListState(onWatchlist = watchlistRepository.contains(stored, movie), seen = false)
        } else {
            movieSeenRepository.insert(MovieSeen(user = stored, movie = movie, seenAt = now))
            watchlistRepository.remove(stored, movie)
            recommendationService.record(stored, movie, RecommendationEventKind.SEEN)
            MovieListState(onWatchlist = false, seen = true)
        }
        recommendationService.refreshLater(stored)
        return state
    }

    /**
     * Gives a seen movie a thumbs up (true), a thumbs down (false), or takes
     * the vote back (null), and returns the state afterwards. A vote on a
     * movie not marked seen marks it seen first, as the vote implies. The
     * vote is what sharpens the recommendations most, so they are recomputed.
     */
    suspend fun vote(user: User, movieId: String, liked: Boolean?): MovieListState {
        val movie = movieRepository.getById(movieId)
        val now = Instant.now()
        val stored = userRepository.touch(user, now)
        val seen = movieSeenRepository.findById(MovieSeenPk(userId = stored.id, movieId = movie.id))
        if (seen != null) {
            movieSeenRepository.update(seen.copy(liked = liked))
        } else {
            movieSeenRepository.insert(MovieSeen(user = stored, movie = movie, seenAt = now, liked = liked))
            watchlistRepository.remove(stored, movie)
            recommendationService.record(stored, movie, RecommendationEventKind.SEEN)
        }
        recommendationService.refreshLater(stored)
        return MovieListState(onWatchlist = false, seen = true, liked = liked)
    }

    /**
     * Marks the titles of an IMDb export as seen ([ImdbExport]), on the day
     * IMDb has for each: the day it was rated, or else added to the list. A
     * movie already seen takes that day too, since IMDb is where the user
     * kept the history. The rating becomes a thumb, 8 and up for a thumbs
     * up and 5 and down for a thumbs down, unless the user gave one here.
     * As with marking one movie, the movies come off the watchlist.
     *
     * The movies are read by id in one query, the seen rows written in one
     * batched upsert, keyed by the pair of user and movie, and the
     * watchlist cleared in one delete, in one transaction; then the
     * recommendations are recomputed in the background.
     */
    suspend fun importFromImdb(user: User, rows: List<ImdbExportRow>, now: Instant = Instant.now()): ImdbImport = transaction {
        val stored = userRepository.touch(user, now)
        val rowsById = rows.associateBy { it.movieId }
        val movies = movieRepository.findAllById(rowsById.keys)
        val before = movieSeenRepository.findAll(stored).associateBy { it.movie.id }
        movieSeenRepository.upsert(movies.map { movie ->
            val row = rowsById.getValue(movie.id)
            val seen = before[movie.id]
            MovieSeen(
                user = stored,
                movie = movie,
                seenAt = row.date?.atStartOfDay(ZoneOffset.UTC)?.toInstant() ?: seen?.seenAt ?: now,
                liked = seen?.liked ?: thumbFor(row.rating)
            )
        })
        watchlistRepository.removeAll(stored, movies)
        recommendationService.refreshLater(stored)
        ImdbImport(
            added = movies.count { it.id !in before },
            updated = movies.count { it.id in before },
            skipped = rowsById.size - movies.size
        )
    }

    /** One page of the movies the user has seen, for the seen page (0-based). */
    fun findPage(user: User, pageNumber: Int): Page<MovieSeen> =
        movieSeenRepository.findPage(user, pageNumber, SEEN_PAGE_SIZE)

    companion object {
        private const val SEEN_PAGE_SIZE = 12

        /** An IMDb rating as a thumb: up from 8, down to 5, none in between, where a movie was fine. */
        fun thumbFor(rating: Int?): Boolean? = when {
            rating == null -> null
            rating >= 8 -> true
            rating <= 5 -> false
            else -> null
        }
    }
}
