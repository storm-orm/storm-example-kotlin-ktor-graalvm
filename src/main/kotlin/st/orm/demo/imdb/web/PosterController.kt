package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves movie posters and person photos through IMDB's public suggestion
 * API and redirects to the CDN image — the API accepts both tconst (tt...)
 * and nconst (nm...) identifiers. Serving images through this endpoint
 * avoids CORS and WAF issues that direct client-side calls to IMDB would
 * hit. Results — including "no image" — are cached for the lifetime of the
 * application.
 */
class PosterController(private val objectMapper: ObjectMapper) {

    private val imageUrlsByImdbId = ConcurrentHashMap<String, Optional<String>>()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun resolveImageUrl(imdbId: String): String? =
        imageUrlsByImdbId.computeIfAbsent(imdbId) { id -> Optional.ofNullable(fetchImageUrl(id)) }.orElse(null)

    /**
     * The suggestion API's video entries carry landscape trailer stills —
     * the closest thing the public API has to a Netflix-style backdrop.
     */
    fun resolveBackdropUrl(imdbId: String): String? =
        imageUrlsByImdbId.computeIfAbsent("backdrop:$imdbId") { Optional.ofNullable(fetchBackdropUrl(imdbId)) }.orElse(null)

    private fun fetchBackdropUrl(imdbId: String): String? = try {
        val uri = URI.create("https://v3.sg.media-imdb.com/suggestion/x/$imdbId.json?includeVideos=1")
        val response = httpClient.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) null else {
            val suggestions = objectMapper.readTree(response.body()).path("d")
            suggestions.firstOrNull { it.path("id").asString("") == imdbId }
                ?.path("v")
                ?.maxByOrNull { video -> video.path("i").path("width").asInt(0) }
                ?.path("i")?.path("imageUrl")?.asString(null)
                ?.replace("._V1_.", "._V1_SX1280.")
        }
    } catch (ignored: Exception) {
        null
    }

    private fun fetchImageUrl(imdbId: String): String? = try {
        val uri = URI.create("https://v3.sg.media-imdb.com/suggestion/x/$imdbId.json")
        val response = httpClient.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) null else {
            val suggestions = objectMapper.readTree(response.body()).path("d")
            suggestions.firstOrNull { it.path("id").asString("") == imdbId }
                ?.path("i")?.path("imageUrl")?.asString(null)
                ?.replace("._V1_.", "._V1_SX400.")
        }
    } catch (ignored: Exception) {
        null
    }
}

/** Responds with a 302 redirect to [url], or 404 when no image resolved. */
private suspend fun ApplicationCall.respondImage(url: String?) {
    if (url != null) respondRedirect(url, permanent = false) else respond(HttpStatusCode.NotFound)
}

fun Route.posterRoutes(posterController: PosterController) {
    // Poster and photo share the same resolver — the suggestion API accepts
    // both tconst (tt...) and nconst (nm...) identifiers.
    for (path in listOf("/api/poster/{imdbId}", "/api/photo/{imdbId}")) {
        get(path) {
            val imdbId = call.parameters.getOrFail("imdbId")
            call.respondImage(withContext(Dispatchers.IO) { posterController.resolveImageUrl(imdbId) })
        }
    }

    get("/api/backdrop/{imdbId}") {
        val imdbId = call.parameters.getOrFail("imdbId")
        call.respondImage(withContext(Dispatchers.IO) { posterController.resolveBackdropUrl(imdbId) })
    }
}
