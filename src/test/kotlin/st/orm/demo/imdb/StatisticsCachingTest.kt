package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.serialization.KotlinxSerializedCache
import st.orm.demo.imdb.service.StatisticsService
import st.orm.demo.imdb.service.StatisticsView
import st.orm.repository.repository
import st.orm.serialization.StormSerializers
import st.orm.template.ORMTemplate
import st.orm.test.StormTest

/**
 * Proves that Storm entities cache safely through full serialization: the
 * statistics view — aggregation results carrying Genre and Person entities —
 * round-trips through JSON without loss, and the cache stores JSON strings
 * rather than object references.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class StatisticsCachingTest {

    @Test
    fun `the statistics view round-trips through JSON without loss`(orm: ORMTemplate) {
        val original = runBlocking { statisticsService(orm).buildStatisticsView() }

        val json = Json { serializersModule = StormSerializers }
        val payload = json.encodeToString(StatisticsView.serializer(), original)
        val decoded = json.decodeFromString(StatisticsView.serializer(), payload)

        // Immutable data classes compare by value: the decoded copy is
        // indistinguishable from the queried original — entities included.
        assertEquals(original, decoded)
    }

    @Test
    fun `the cache stores the view as serialized JSON`(orm: ORMTemplate) {
        val view = runBlocking { statisticsService(orm).buildStatisticsView() }
        val cache = KotlinxSerializedCache(StatisticsService.STATISTICS_CACHE, StatisticsView.serializer())

        cache.put("statistics", view)

        // The native store holds a JSON string — like Redis would — not an
        // object reference; reading decodes it back into an equal value.
        assertTrue(cache.nativeCache["statistics"] is String)
        assertEquals(view, cache.get("statistics"))
    }

    private fun statisticsService(orm: ORMTemplate) = StatisticsService(
        movieRepository = orm.repository(),
        movieGenreRepository = orm.repository(),
        principalRepository = orm.repository()
    )
}
