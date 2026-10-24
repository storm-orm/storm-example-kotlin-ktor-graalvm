package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import st.orm.demo.imdb.repository.DecadeMovieCount
import st.orm.demo.imdb.repository.GenreMovieCount
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.GenreRatingStatistics
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RecommendationEventCount
import st.orm.demo.imdb.repository.RecommendationEventRepository
import st.orm.demo.imdb.repository.ProlificActor
import st.orm.demo.imdb.serialization.KotlinxSerializedCache
import st.orm.template.transaction

/** The size of the catalogue: how many movies, actors, people and credits it holds. */
@Serializable
data class CatalogueTotals(
    val movies: Long,
    val actors: Long,
    val people: Long,
    val credits: Long
)

/**
 * Everything the statistics page shows. Serializable, including the Storm
 * entities inside the result types, so the whole view can round-trip
 * through the serialized cache.
 */
@Serializable
data class StatisticsView(
    val totals: CatalogueTotals,
    val decades: List<DecadeMovieCount>,
    val maxDecadeCount: Long,
    val genreStatistics: List<GenreRatingStatistics>,
    val prolificActors: List<ProlificActor>
)

class StatisticsService(
    private val movieRepository: MovieRepository,
    private val genreRepository: GenreRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val personRepository: PersonRepository,
    private val principalRepository: PrincipalRepository,
    private val recommendationEventRepository: RecommendationEventRepository
) {

    /**
     * How often recommendations led somewhere, per kind. Read live, not from
     * the cache: the events change with every visit, the catalogue statistics
     * with the daily sync, and the count is one indexed query.
     */
    fun countRecommendationEvents(): List<RecommendationEventCount> =
        recommendationEventRepository.countByKind()

    // The statistics view is cached as serialized JSON (see
    // KotlinxSerializedCache) rather than as an object reference — the way an
    // external store such as Redis would — proving Storm's immutable entities
    // survive the round-trip unchanged. This replaces Spring's @Cacheable,
    // which does not exist outside Spring; get-or-compute is explicit instead.
    private val cache = KotlinxSerializedCache(STATISTICS_CACHE, StatisticsView.serializer())

    /**
     * All aggregate sections, served from the cache when present and otherwise
     * computed in one read-only transaction and stored. Because both the cache
     * lookup and the query path are coroutine-native, this is a plain suspend
     * function opening its transaction with the suspend `transaction { }`.
     */
    suspend fun buildStatisticsView(): StatisticsView =
        cache.get(STATISTICS_CACHE) { computeStatisticsView() }

    /**
     * The genres with their movie counts, for the genre navigation on the
     * home page. A count over every movie's genres takes a few hundred
     * milliseconds on the hosted database, and only the daily sync changes
     * it, so it is a catalogue statistic like the ones above: cached the same
     * way, under a key of its own so the home page never waits for the whole
     * view, and refreshed with it ([refreshCatalogueStatistics]).
     */
    suspend fun genreCounts(): List<GenreMovieCount> =
        genreCountsCache.get(GENRE_COUNTS_CACHE) { computeGenreCounts() }

    private val genreCountsCache = KotlinxSerializedCache(GENRE_COUNTS_CACHE, ListSerializer(GenreMovieCount.serializer()))

    private suspend fun computeGenreCounts(): List<GenreMovieCount> = transaction(readOnly = true) {
        genreRepository.findGenresWithMovieCounts()
    }

    /** Recomputes the cached catalogue statistics and replaces them, once the data behind them has changed. */
    suspend fun refreshCatalogueStatistics() {
        cache.put(STATISTICS_CACHE, computeStatisticsView())
        genreCountsCache.put(GENRE_COUNTS_CACHE, computeGenreCounts())
    }

    private suspend fun computeStatisticsView(): StatisticsView = transaction(readOnly = true) {
        val decades = movieRepository.countMoviesPerDecade()
        StatisticsView(
            totals = CatalogueTotals(
                movies = movieRepository.count(),
                actors = principalRepository.countActors(),
                people = personRepository.count(),
                credits = principalRepository.count()
            ),
            decades = decades,
            maxDecadeCount = decades.maxOfOrNull { it.movieCount } ?: 1L,
            genreStatistics = movieGenreRepository.findGenreRatingStatistics(
                GENRE_MINIMUM_MOVIE_COUNT, GENRE_LIMIT),
            prolificActors = principalRepository.findMostProlificActors(ACTOR_LIMIT)
        )
    }

    companion object {
        const val STATISTICS_CACHE = "statistics"
        const val GENRE_COUNTS_CACHE = "genre-counts"
        private const val GENRE_MINIMUM_MOVIE_COUNT = 50
        private const val GENRE_LIMIT = 10
        private const val ACTOR_LIMIT = 10
    }
}
