package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What a section's wall is made of: the visitor's recommendations, or the movies they watched. */
enum class WallKind { RECOMMENDED, WATCHED }

/**
 * The walls of posters behind the section pages' titles: the Movies pages
 * drift the visitor's recommendations, the My Movies pages what they
 * watched, the latest first. A visitor with too few of either gets the
 * best-known movies of their taste. A wall is kept in memory a while per
 * visitor, so the pages pay one query for it, now and then.
 */
class WallService(
    private val recommendationRepository: RecommendationRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val ratingRepository: RatingRepository
) {
    private class Kept(val at: Instant, val rows: List<List<Movie>>)

    private val walls = ConcurrentHashMap<String, Kept>()

    /** The wall's rows of posters, a third each, for the visitor and the kind. */
    suspend fun wallOf(user: User, kind: WallKind, now: Instant = Instant.now()): List<List<Movie>> {
        val key = "${user.id}:$kind"
        walls[key]?.takeIf { Duration.between(it.at, now) < KEPT }?.let { return it.rows }
        if (walls.size > MOST_KEPT) walls.entries.removeIf { Duration.between(it.value.at, now) >= KEPT }
        val movies = transaction(readOnly = true) {
            val own = when (kind) {
                WallKind.RECOMMENDED -> recommendationRepository.findAll(user).map { it.movie }.shuffled()
                WallKind.WATCHED -> movieSeenRepository.findMostRecent(user, POSTERS).map { it.movie }
            }
            // Too few of the visitor's own to fill a wall: the best-known movies of their taste.
            if (own.size >= FEWEST) own.take(POSTERS)
            else ratingRepository.findBestKnown(user.movieFilter, WELL_KNOWN_VOTES, POOL).map { it.movie }.shuffled().take(POSTERS)
        }
        val rows = movies.chunked(((movies.size + 2) / 3).coerceAtLeast(1))
        walls[key] = Kept(now, rows)
        return rows
    }

    private companion object {
        const val POSTERS = 36
        const val FEWEST = 12
        const val POOL = 100
        const val WELL_KNOWN_VOTES = 25_000
        const val MOST_KEPT = 500
        val KEPT: Duration = Duration.ofMinutes(10)
    }
}
