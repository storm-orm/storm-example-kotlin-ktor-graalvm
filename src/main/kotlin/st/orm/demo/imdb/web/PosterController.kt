package st.orm.demo.imdb.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import st.orm.demo.imdb.service.ObjectBucket
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * What a lookup of an image found: its URL, no image at all, or nothing yet
 * because IMDB could not be asked or did not answer. Only the first two are
 * answers worth keeping.
 */
sealed interface ImageLookup {
    data class Found(val url: String) : ImageLookup
    data object None : ImageLookup
    data object Failed : ImageLookup
}

/**
 * Resolves movie posters and person photos through IMDB's public suggestion
 * API — the API accepts both tconst (tt...) and nconst (nm...) identifiers.
 * Serving images through this endpoint avoids CORS and WAF issues that
 * direct client-side calls to IMDB would hit.
 *
 * With the deployment's [bucket], an image found is stored there, and so is
 * an image IMDB has none for, as an empty object: an image is looked up on
 * IMDB once, not once per start of the application, and the browser loads
 * it from the bucket. Without one, the browser is sent to IMDB's CDN. Either
 * way an answer is kept in memory for the lifetime of the application; a
 * failed lookup is not, so the next request asks again. IMDB answers too
 * many lookups at once with 429: the lookups take turns, a few at a time,
 * and after a 429 none is made for a while.
 */
class PosterController(private val objectMapper: ObjectMapper, private val bucket: ObjectBucket? = null) {

    private val logger = LoggerFactory.getLogger(PosterController::class.java)

    private val lookupsByImdbId = ConcurrentHashMap<String, ImageLookup>()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private val lookups = Semaphore(PARALLEL_LOOKUPS)

    /** Until when IMDB is not asked, after it answered 429. */
    @Volatile private var pausedUntil: Instant = Instant.EPOCH

    fun resolveImage(imdbId: String): ImageLookup =
        cached("images/$imdbId.jpg") { fetch(imdbId, includeVideos = false) { entry ->
            entry.path("i").path("imageUrl").asString(null)?.replace("._V1_.", "._V1_SX400.")
        } }

    /**
     * The suggestion API's video entries carry landscape trailer stills —
     * the closest thing the public API has to a Netflix-style backdrop.
     */
    fun resolveBackdrop(imdbId: String): ImageLookup =
        cached("backdrops/$imdbId.jpg") { fetch(imdbId, includeVideos = true) { entry ->
            entry.path("v")
                .maxByOrNull { video -> video.path("i").path("width").asInt(0) }
                ?.path("i")?.path("imageUrl")?.asString(null)
                ?.replace("._V1_.", "._V1_SX1280.")
        } }

    /**
     * The image under [key]: as answered before, else from the bucket, else
     * looked up on IMDB and stored in the bucket, an empty object where IMDB
     * has none. Found in the bucket, the answer is a link to it there.
     */
    private fun cached(key: String, fetch: () -> ImageLookup): ImageLookup =
        lookupsByImdbId[key] ?: (if (bucket == null) fetch() else fromBucket(bucket, key, fetch))
            .also { if (it != ImageLookup.Failed) lookupsByImdbId[key] = it }

    private fun fromBucket(bucket: ObjectBucket, key: String, fetch: () -> ImageLookup): ImageLookup {
        val size = try {
            bucket.size(key)
        } catch (e: Exception) {
            // The bucket cannot be reached: IMDB's CDN serves, as without a bucket.
            logger.warn("The image cache could not be read for {}: {}.", key, e.toString())
            return fetch()
        }
        if (size != null) return if (size > 0) ImageLookup.Found(linkTo(bucket, key)) else ImageLookup.None
        return when (val found = fetch()) {
            is ImageLookup.Found -> store(bucket, key, found)
            ImageLookup.None -> found.also { runCatching { bucket.put(key, ByteArray(0), "image/jpeg", CACHE_CONTROL) } }
            ImageLookup.Failed -> found
        }
    }

    /** Copies the image from IMDB's CDN into the bucket and links to it there; the CDN's link where that fails. */
    private fun store(bucket: ObjectBucket, key: String, found: ImageLookup.Found): ImageLookup = try {
        val response = httpClient.send(HttpRequest.newBuilder(URI.create(found.url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "IMDB's CDN answered ${response.statusCode()}" }
        val type = response.headers().firstValue("Content-Type").orElse("image/jpeg")
        bucket.put(key, response.body(), type, CACHE_CONTROL)
        ImageLookup.Found(linkTo(bucket, key))
    } catch (e: Exception) {
        logger.warn("The image {} could not be stored: {}.", key, e.toString())
        found
    }

    /**
     * A link to the image in the bucket, signed at the start of the day and
     * valid for a week: the same link all day, so the browser loads an image
     * once a day at most, and still valid after the day a browser keeps the
     * redirect to it.
     */
    private fun linkTo(bucket: ObjectBucket, key: String): String =
        bucket.link(key, LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC), LINK_VALIDITY)

    private fun fetch(imdbId: String, includeVideos: Boolean, image: (JsonNode) -> String?): ImageLookup {
        if (Instant.now().isBefore(pausedUntil)) return ImageLookup.Failed
        lookups.acquire()
        return try {
            val uri = URI.create("https://v3.sg.media-imdb.com/suggestion/x/$imdbId.json" + if (includeVideos) "?includeVideos=1" else "")
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> objectMapper.readTree(response.body()).path("d")
                    .firstOrNull { it.path("id").asString("") == imdbId }
                    ?.let(image)
                    ?.let { ImageLookup.Found(it) }
                    ?: ImageLookup.None
                else -> {
                    if (response.statusCode() == 429) pausedUntil = Instant.now().plus(PAUSE_AFTER_TOO_MANY)
                    logger.warn("Image lookup for {} failed: IMDB answered {}.", imdbId, response.statusCode())
                    ImageLookup.Failed
                }
            }
        } catch (e: Exception) {
            logger.warn("Image lookup for {} failed: {}.", imdbId, e.toString())
            ImageLookup.Failed
        } finally {
            lookups.release()
        }
    }

    private companion object {
        /** The images in the bucket do not change: a browser keeps one a week. */
        const val CACHE_CONTROL = "public, max-age=604800, immutable"
        val LINK_VALIDITY: Duration = Duration.ofDays(7)
        /** How many lookups IMDB is asked at once. */
        const val PARALLEL_LOOKUPS = 4
        val PAUSE_AFTER_TOO_MANY: Duration = Duration.ofSeconds(30)
    }
}

/**
 * Responds with a 302 redirect to the image, or 404 when there is none; both are answers, which
 * the browser caches for a day, as they are cached here: without it the browser asks again for
 * every image each time a page or the search dropdown shows it. A failed lookup answers 503 and
 * is not cached, so the image is asked for again the next time it is shown.
 */
private suspend fun ApplicationCall.respondImage(lookup: ImageLookup) {
    when (lookup) {
        is ImageLookup.Found -> {
            response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            respondRedirect(lookup.url, permanent = false)
        }
        ImageLookup.None -> {
            response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            respond(HttpStatusCode.NotFound)
        }
        ImageLookup.Failed -> {
            response.header(HttpHeaders.CacheControl, "no-store")
            respond(HttpStatusCode.ServiceUnavailable)
        }
    }
}

fun Route.posterRoutes(posterController: PosterController) {
    // Poster and photo share the same resolver — the suggestion API accepts
    // both tconst (tt...) and nconst (nm...) identifiers.
    for (path in listOf("/api/poster/{imdbId}", "/api/photo/{imdbId}")) {
        get(path) {
            val imdbId = call.parameters.getOrFail("imdbId")
            call.respondImage(withContext(Dispatchers.IO) { posterController.resolveImage(imdbId) })
        }
    }

    get("/api/backdrop/{imdbId}") {
        val imdbId = call.parameters.getOrFail("imdbId")
        call.respondImage(withContext(Dispatchers.IO) { posterController.resolveBackdrop(imdbId) })
    }
}
