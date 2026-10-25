package st.orm.demo.imdb.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.TmdbSource
import st.orm.demo.imdb.model.WatchProvider
import st.orm.demo.imdb.model.WatchProviders
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.TmdbSourceRepository
import st.orm.demo.imdb.repository.WatchProviderRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger(TmdbService::class.java)

/**
 * TMDB as a source next to IMDb: the trailers and the platforms on the movie
 * pages and For You's posters, the services of a country, and the movies a
 * TMDB export names by their TMDB ids. What TMDB answers about a movie is
 * stored as its TMDB source ([TmdbSource]) for six months, the most TMDB's
 * terms allow: a page reads it from the database, a movie asked for after
 * that is fetched again, and the rest is removed. Where a movie plays
 * changes more often, so a page that shows the platforms asks again after a
 * week. The calls run outside any transaction, so no connection waits on
 * TMDB. Only a signed-in visitor's request asks TMDB ([askTmdb]): anyone else,
 * a crawler included, gets what is stored, so no visit can run up calls.
 * Without an API key, there are no trailers and no platforms, and a TMDB
 * export brings only the movies it gives IMDb ids for.
 */
class TmdbService(
    private val movieRepository: MovieRepository,
    private val tmdbSourceRepository: TmdbSourceRepository,
    private val watchProviderRepository: WatchProviderRepository,
    private val client: TmdbClient,
    private val keep: Duration = Duration.ofDays(180),
    private val keepWatchProviders: Duration = Duration.ofDays(7)
) {
    val enabled: Boolean get() = client.enabled

    /**
     * One lookup of a movie at a time: a movie page asks for the trailer and
     * the platforms at once, and the second waits for the answer to the
     * first rather than asking TMDB again. A lock per stripe of movies, so
     * the lookups of other movies, like the posters of For You, run alongside.
     */
    private val lookups = Array(LOOKUP_STRIPES) { Mutex() }

    private fun lockOf(movie: Movie) = lookups[Math.floorMod(movie.id.hashCode(), LOOKUP_STRIPES)]

    /** The services of a country, the same for everyone there: kept in memory, and asked again after a day. */
    private class Region(val fetchedAt: Instant, val providers: List<WatchProvider>)

    private val regions = ConcurrentHashMap<String, Region>()

    /**
     * The YouTube key of a movie's trailer, or null when it has none, TMDB
     * does not know the movie, or TMDB is off. A failed call stores nothing
     * and answers with what was stored before; so does a request that may
     * not ask TMDB ([askTmdb]).
     */
    suspend fun trailer(movieId: String, askTmdb: Boolean, now: Instant = Instant.now()): String? =
        source(movieId, askTmdb, now) { it.fetchedAt.isAfter(now.minus(keep)) }?.trailerKey

    /**
     * The platforms a movie streams on or shows free in a country, in TMDB's
     * order; none when it plays nowhere there, TMDB does not know the movie,
     * or TMDB is off. As stored for a request that may not ask TMDB.
     */
    suspend fun watchProviders(movieId: String, country: String, askTmdb: Boolean, now: Instant = Instant.now()): List<WatchProvider> {
        val ids = source(movieId, askTmdb, now, freshWatchProviders(now))?.watchProviders?.byCountry?.get(country) ?: return emptyList()
        val providers = transaction(readOnly = true) { watchProviderRepository.findAllById(ids) }.associateBy { it.id }
        return ids.mapNotNull { providers[it] }
    }

    /**
     * Where each of these movies streams or shows free in a country, as the
     * ids of the platforms by movie id, for the posters and the share of the
     * user's services: what is stored and fresh is read in one query, the
     * rest asked of TMDB a few calls at a time, at most [maxLookups] of them;
     * the others answer as stored. A movie TMDB cannot place plays nowhere,
     * and a movie whose platforms are not known yet is left out.
     */
    suspend fun watchProviderIds(
        movies: List<Movie>,
        country: String,
        maxLookups: Int = Int.MAX_VALUE,
        now: Instant = Instant.now()
    ): Map<String, List<Int>> {
        if (!enabled || movies.isEmpty()) return emptyMap()
        val fresh = freshWatchProviders(now)
        val stored = transaction(readOnly = true) { tmdbSourceRepository.findByMovies(movies) }.associateBy { it.movie.entityId() }
        val lookUps = movies.filter { stored[it.id]?.let(fresh) != true }.take(maxLookups).toSet()
        val calls = Semaphore(PARALLEL_CALLS)
        return coroutineScope {
            movies.map { movie ->
                async {
                    val source = if (movie in lookUps) calls.withPermit { lookUpOnce(movie, now, fresh) } else stored[movie.id]
                    source?.watchProviders?.let { movie.id to it.byCountry[country].orEmpty() }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /**
     * The streaming services active in a country, the most watched there
     * first, for the account page: as TMDB answered within the day, each
     * stored as a platform, so a user's choice can name it. None when TMDB
     * is off; as answered before when it cannot be reached.
     */
    suspend fun watchProvidersIn(country: String, now: Instant = Instant.now()): List<WatchProvider> {
        if (!enabled) return emptyList()
        val cached = regions[country]
        if (cached != null && cached.fetchedAt.isAfter(now.minus(KEEP_REGION))) return cached.providers
        val providers = try {
            withContext(Dispatchers.IO) { client.watchProvidersIn(country) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Asking TMDB for the services in {} failed: {}", country, e.message)
            return cached?.providers.orEmpty()
        }
        transaction { watchProviderRepository.store(providers) }
        regions[country] = Region(now, providers)
        return providers
    }

    /** Whether a source names the platforms, as TMDB answered within the week. */
    private fun freshWatchProviders(now: Instant): (TmdbSource) -> Boolean =
        { it.watchProviders != null && it.fetchedAt.isAfter(now.minus(keepWatchProviders)) }

    /**
     * What TMDB answered about a movie: as stored while [fresh], else asked
     * again and stored, or as stored when TMDB cannot be reached or the
     * request may not ask it ([askTmdb]). Null for a movie the catalogue
     * does not have, and when TMDB is off.
     */
    private suspend fun source(movieId: String, askTmdb: Boolean, now: Instant, fresh: (TmdbSource) -> Boolean): TmdbSource? {
        if (!enabled) return null
        val (movie, stored) = transaction(readOnly = true) {
            movieRepository.findById(movieId)?.let { it to tmdbSourceRepository.findByMovie(it) }
        } ?: return null
        return if ((stored != null && fresh(stored)) || !askTmdb) stored else lookUpOnce(movie, now, fresh)
    }

    /**
     * Asks TMDB about a movie under its lock: whoever waited finds the answer
     * the first one stored, and asks no more. As stored when TMDB cannot be reached.
     */
    private suspend fun lookUpOnce(movie: Movie, now: Instant, fresh: (TmdbSource) -> Boolean): TmdbSource? =
        lockOf(movie).withLock {
            val current = transaction(readOnly = true) { tmdbSourceRepository.findByMovie(movie) }
            if (current != null && fresh(current)) current else lookUp(movie, now) ?: current
        }

    /** Asks TMDB about a movie and stores the answer, with the platforms it names; null when TMDB cannot be reached. */
    private suspend fun lookUp(movie: Movie, now: Instant): TmdbSource? {
        val tmdb = try {
            withContext(Dispatchers.IO) { client.findTmdbId(movie.id)?.let(client::movie) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Looking up {} ({}) on TMDB failed: {}", movie.primaryTitle, movie.id, e.message)
            return null
        }
        return transaction { store(movie, tmdb, now) }
    }

    /**
     * Stores what TMDB answered about a movie, null where TMDB does not know
     * it, as its TMDB source, and the platforms it plays on.
     */
    private fun store(movie: Movie, tmdb: TmdbMovie?, now: Instant): TmdbSource {
        val platforms = tmdb?.watchProviders.orEmpty()
        watchProviderRepository.store(platforms.values.flatten())
        val source = TmdbSource(
            movie = movie.ref(),
            externalId = tmdb?.tmdbId?.toString(),
            trailerKey = tmdb?.trailerKey,
            watchProviders = WatchProviders(platforms.mapValues { (_, providers) -> providers.map { it.id } }),
            fetchedAt = now
        )
        tmdbSourceRepository.save(source)
        return source
    }

    /**
     * The movies with these TMDB ids, by TMDB id, for an import of a TMDB
     * export: the stored sources first, then the others from TMDB, a few
     * calls at a time and at most [MAX_LOOKUPS] per import, each stored as
     * the movie's TMDB source for the next time; only the stored ones for an
     * import that may not ask TMDB. A movie TMDB cannot place, or one the
     * catalogue does not keep, is left out.
     */
    suspend fun movieIds(tmdbIds: Set<String>, askTmdb: Boolean, now: Instant = Instant.now()): Map<String, String> {
        val known = transaction(readOnly = true) { tmdbSourceRepository.findByExternalIds(tmdbIds) }
            .associate { it.externalId!! to it.movie.entityId() }
        val missing = (tmdbIds - known.keys).mapNotNull { it.toIntOrNull() }.take(MAX_LOOKUPS)
        if (missing.isEmpty() || !enabled || !askTmdb) return known
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
            movieRepository.findAllById(byImdbId.keys).onEach { movie -> store(movie, byImdbId.getValue(movie.id), now) }
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
        private const val LOOKUP_STRIPES = 64
        private val KEEP_REGION: Duration = Duration.ofDays(1)
    }
}
