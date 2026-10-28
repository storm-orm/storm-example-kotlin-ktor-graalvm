package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.template.entityId
import st.orm.template.ref

/**
 * The movies a match is played from: well-known movies of its [kind], with
 * their ratings, the best known first; how many of them each player has
 * watched, by user id, which says who knows the pool; and whether the kind
 * gave too few, so the house fell back on the well-known movies of the
 * host's taste.
 */
data class MoviePool(val kind: PoolKind, val ratings: List<Rating>, val seenCounts: Map<String, Int>, val fellBack: Boolean) {
    val movies: List<Movie> get() = ratings.map { it.movie }
    val size: Int get() = ratings.size
}

/**
 * Makes the pool a game is played from, out of what its players have
 * watched ([PoolKind]): the well-known movies the host's taste lets through
 * that all of them watched, or any, or none; or the movies the site
 * recommends them, the ones most of them were recommended first. Too few,
 * and the house falls back on the well-known movies of the host's taste,
 * which the match says.
 */
class PoolService(
    private val ratingRepository: RatingRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val recommendationRepository: RecommendationRepository
) {

    /** The pool for [players], inside the caller's transaction; at least [minimum] movies, or the fallback. */
    fun poolOf(host: User, players: List<User>, kind: PoolKind, minimumVotes: Int, limit: Int, minimum: Int = MINIMUM): MoviePool {
        val filter = host.movieFilter
        val seenBy = movieSeenRepository.findSeenBy(players).groupBy({ it.movie.entityId() }, { it.user.entityId() })
        val known = wellKnown(filter, minimumVotes)
        val chosen = when (kind) {
            PoolKind.INTERSECTION -> known.filter { seenBy[it.movie.id]?.size == players.size }
            PoolKind.UNION -> known.filter { it.movie.id in seenBy }
            PoolKind.UNSEEN -> known.filter { it.movie.id !in seenBy }
            PoolKind.RECOMMENDED -> recommended(players, seenBy.keys, minimumVotes)
        }.take(limit)
        val ratings = chosen.ifEmpty { known.take(limit) }.let { if (chosen.size < minimum) fallback(filter, minimumVotes, limit, seenBy.keys, kind) else it }
        val ids = ratings.map { it.movie.id }.toSet()
        val counts = players.associate { player -> player.id to ids.count { seenBy[it]?.contains(player.id) == true } }
        return MoviePool(kind, ratings, counts, fellBack = chosen.size < minimum)
    }

    /** The well-known movies the taste lets through, the best known first; everybody's when the taste lets none. */
    private fun wellKnown(filter: MovieFilter?, minimumVotes: Int): List<Rating> =
        ratingRepository.findBestKnown(filter, minimumVotes, SCAN).ifEmpty { ratingRepository.findBestKnown(null, minimumVotes, SCAN) }

    /**
     * The movies recommended to the players, none of them has watched, the
     * ones recommended to most of them first, then the best scored.
     */
    private fun recommended(players: List<User>, seen: Set<String>, minimumVotes: Int): List<Rating> {
        val picks = recommendationRepository.findAllOf(players).filter { it.movie.id !in seen }
        if (picks.isEmpty()) return emptyList()
        val byMovie = picks.groupBy { it.movie.id }
        val order = byMovie.entries.sortedWith(compareByDescending<Map.Entry<String, List<Recommendation>>> { it.value.size }
            .thenByDescending { entry -> entry.value.maxOf { it.score } }).map { it.key }
        val ratings = ratingRepository.findByMovies(byMovie.values.map { it.first().movie.ref() }).associateBy { it.movie.id }
        return order.mapNotNull { ratings[it] }.filter { it.voteCount >= minimumVotes }
    }

    /** What a pool too small falls back on: the well-known movies of the taste, the ones nobody watched for a pool of new movies. */
    private fun fallback(filter: MovieFilter?, minimumVotes: Int, limit: Int, seen: Set<String>, kind: PoolKind): List<Rating> {
        val known = wellKnown(filter, minimumVotes)
        return (if (kind == PoolKind.RECOMMENDED || kind == PoolKind.UNSEEN) known.filter { it.movie.id !in seen } else known).take(limit)
    }

    companion object {
        /** A pool smaller than this is not worth playing from: the house falls back. */
        const val MINIMUM = 12
        /** The well-known movies looked through: the catalogue's most voted. */
        private const val SCAN = 3_000
    }
}
