package st.orm.demo.imdb.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.TmdbSource
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.TmdbSourceRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant

private val logger = LoggerFactory.getLogger(TmdbService::class.java)

/**
 * TMDB as a source next to IMDb: the trailers on the movie pages, and the
 * movies a TMDB export names by their TMDB ids. What TMDB answers is stored
 * as the movie's TMDB source ([TmdbSource]) for six months, the most TMDB's
 * terms allow: a page reads it from the database, a movie asked for after
 * that is fetched again, and the rest is removed. The calls run outside any
 * transaction, so no connection waits on TMDB. Without an API key, there are
 * no trailers, and a TMDB export brings only the movies it gives IMDb ids for.
 */
class TmdbService(
    private val movieRepository: MovieRepository,
    private val tmdbSourceRepository: TmdbSourceRepository,
    private val client: TmdbClient,
    private val keep: Duration = Duration.ofDays(180)
) {
    val enabled: Boolean get() = client.enabled

    /**
     * The YouTube key of a movie's trailer, or null when it has none, TMDB
     * does not know the movie, or TMDB is off. A failed call stores nothing
     * and answers with what was stored before.
     */
    suspend fun trailer(movieId: String, now: Instant = Instant.now()): String? {
        if (!enabled) return null
        val (movie, stored) = transaction(readOnly = true) {
            movieRepository.findById(movieId)?.let { it to tmdbSourceRepository.findByMovie(it) }
        } ?: return null
        if (stored != null && stored.fetchedAt.isAfter(now.minus(keep))) return stored.trailerKey
        val fetched = fetch(movie, now) ?: return stored?.trailerKey
        transaction { tmdbSourceRepository.save(fetched) }
        return fetched.trailerKey
    }

    private suspend fun fetch(movie: Movie, now: Instant): TmdbSource? = withContext(Dispatchers.IO) {
        try {
            val tmdbId = client.findTmdbId(movie.id)
            TmdbSource(movie = movie.ref(), externalId = tmdbId?.toString(), trailerKey = tmdbId?.let(client::movie)?.trailerKey, fetchedAt = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Looking up {} ({}) on TMDB failed: {}", movie.primaryTitle, movie.id, e.message)
            null
        }
    }

    /**
     * The movies with these TMDB ids, by TMDB id, for an import of a TMDB
     * export: the stored sources first, then the others from TMDB, a few
     * calls at a time and at most [MAX_LOOKUPS] per import, each stored as
     * the movie's TMDB source for the next time. A movie TMDB cannot place,
     * or one the catalogue does not keep, is left out.
     */
    suspend fun movieIds(tmdbIds: Set<String>, now: Instant = Instant.now()): Map<String, String> {
        val known = transaction(readOnly = true) { tmdbSourceRepository.findByExternalIds(tmdbIds) }
            .associate { it.externalId!! to it.movie.entityId() }
        val missing = (tmdbIds - known.keys).mapNotNull { it.toIntOrNull() }.take(MAX_LOOKUPS)
        if (missing.isEmpty() || !enabled) return known
        val calls = Semaphore(PARALLEL_CALLS)
        val found = coroutineScope {
            missing.map { tmdbId ->
                async(Dispatchers.IO) {
                    calls.withPermit {
                        try {
                            client.movie(tmdbId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Looking up TMDB movie {} failed: {}", tmdbId, e.message)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val byImdbId = found.filter { it.imdbId != null }.associateBy { it.imdbId!! }
        val movies = transaction {
            movieRepository.findAllById(byImdbId.keys).onEach { movie ->
                val tmdb = byImdbId.getValue(movie.id)
                tmdbSourceRepository.save(TmdbSource(movie = movie.ref(), externalId = tmdb.tmdbId.toString(), trailerKey = tmdb.trailerKey, fetchedAt = now))
            }
        }
        return known + movies.associate { byImdbId.getValue(it.id).tmdbId.toString() to it.id }
    }

    /** Removes what TMDB answered longer ago than its terms let it be kept, for movies nobody asked for since. */
    suspend fun removeExpired(now: Instant = Instant.now()) = transaction {
        tmdbSourceRepository.removeFetchedBefore(now.minus(keep))
    }

    companion object {
        /** The most movies one import looks up on TMDB; importing the file again looks up the rest. */
        const val MAX_LOOKUPS = 1000
        private const val PARALLEL_CALLS = 6
    }
}
