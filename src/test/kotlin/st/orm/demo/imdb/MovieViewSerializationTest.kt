package st.orm.demo.imdb

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.repository.repository
import st.orm.serialization.StormSerializers
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.StormTest
import java.time.Instant

/**
 * Demonstrates Ref serialization: the Contextual Ref field on MovieView is
 * handled by StormSerializers — an unloaded ref serializes as the raw
 * primary key, a loaded ref as the embedded entity — and both round-trip
 * losslessly.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class MovieViewSerializationTest {

    private val json = Json { serializersModule = StormSerializers }

    @Test
    fun `a view with an unloaded ref serializes as the raw movie id`(orm: ORMTemplate) {
        val movieViewRepository = orm.repository<MovieViewRepository>()
        // Views load their movie as an unloaded Ref — just the id.
        val recentView = movieViewRepository.findRecentViews(1).single()

        val payload = json.encodeToString(MovieView.serializer(), recentView)
        assertTrue(payload.contains("\"movie\":\"tt0133093\"")) { "Unloaded ref is the raw PK: $payload" }

        val decoded = json.decodeFromString(MovieView.serializer(), payload)
        assertEquals(recentView, decoded)
    }

    @Test
    fun `a view with a loaded ref serializes the embedded movie entity`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        val theMatrix = movieRepository.getById("tt0133093")
        val view = MovieView(id = 42, movie = theMatrix.ref(), viewedAt = Instant.parse("2026-07-01T10:00:00Z"))

        val payload = json.encodeToString(MovieView.serializer(), view)
        assertTrue(payload.contains("\"@entity\"")) { "Loaded ref embeds the entity: $payload" }
        assertTrue(payload.contains("The Matrix"))

        val decoded = json.decodeFromString(MovieView.serializer(), payload)
        assertEquals(view, decoded)
    }
}
