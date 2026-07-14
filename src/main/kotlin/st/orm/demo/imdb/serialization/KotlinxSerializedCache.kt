package st.orm.demo.imdb.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import st.orm.serialization.StormSerializers
import java.util.concurrent.ConcurrentHashMap

/**
 * A cache that stores its values as serialized JSON — the way an external
 * cache like Redis would. Every hit decodes the payload back into objects,
 * so each read exercises the full serialization round-trip.
 *
 * This works because Storm entities are immutable data classes: no proxies,
 * no session state, no lazy-loading surprises — what serializes is exactly
 * what was queried, and the decoded copy compares equal to it. The Json
 * instance carries StormSerializers so entity graphs containing Ref fields
 * serialize correctly as well.
 */
class KotlinxSerializedCache<T : Any>(
    val name: String,
    private val serializer: KSerializer<T>,
    private val json: Json = Json { serializersModule = StormSerializers }
) {

    // The native store holds JSON strings — like Redis would — not object
    // references.
    val nativeCache: MutableMap<String, String> = ConcurrentHashMap()

    /** Stores [value] under [key] as its serialized JSON form. */
    fun put(key: String, value: T) {
        nativeCache[key] = json.encodeToString(serializer, value)
    }

    /** Returns the decoded value for [key], or null when it is not cached. */
    fun get(key: String): T? =
        nativeCache[key]?.let { json.decodeFromString(serializer, it) }

    /**
     * Returns the cached value for [key], or computes it with [loader], stores
     * the serialized result, and returns it. The loader is a suspend function
     * so callers can open a Storm transaction inside it.
     */
    suspend fun get(key: String, loader: suspend () -> T): T =
        get(key) ?: loader().also { put(key, it) }
}
