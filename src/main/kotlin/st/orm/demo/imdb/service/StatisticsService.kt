package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.repository.DecadeMovieCount
import st.orm.demo.imdb.repository.GenreRatingStatistics
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.ProlificActor
import st.orm.demo.imdb.serialization.KotlinxSerializedCache
import st.orm.template.transaction

/**
 * Everything the statistics page shows. Serializable, including the Storm
 * entities inside the result types, so the whole view can round-trip
 * through the serialized cache.
 */
@Serializable
data class StatisticsView(
    val decades: List<DecadeMovieCount>,
    val maxDecadeCount: Long,
    val genreStatistics: List<GenreRatingStatistics>,
    val prolificActors: List<ProlificActor>
)

class StatisticsService(
    private val movieRepository: MovieRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val principalRepository: PrincipalRepository
) {

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
    suspend fun buildStatisticsView(): StatisticsView = cache.get(STATISTICS_CACHE) {
        transaction(readOnly = true) {
            val decades = movieRepository.countMoviesPerDecade()
            StatisticsView(
                decades = decades,
                maxDecadeCount = decades.maxOfOrNull { it.movieCount } ?: 1L,
                genreStatistics = movieGenreRepository.findGenreRatingStatistics(
                    GENRE_MINIMUM_MOVIE_COUNT, GENRE_LIMIT),
                prolificActors = principalRepository.findMostProlificActors(ACTOR_LIMIT)
            )
        }
    }

    companion object {
        const val STATISTICS_CACHE = "statistics"
        private const val GENRE_MINIMUM_MOVIE_COUNT = 50
        private const val GENRE_LIMIT = 10
        private const val ACTOR_LIMIT = 10
    }
}
