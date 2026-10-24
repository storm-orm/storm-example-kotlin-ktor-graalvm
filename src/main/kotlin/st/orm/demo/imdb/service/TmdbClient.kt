package st.orm.demo.imdb.service

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A movie as TMDB has it: its TMDB id, its IMDb id if TMDB knows it, and the YouTube key of its trailer, if any. */
data class TmdbMovie(val tmdbId: Int, val imdbId: String?, val trailerKey: String?)

/**
 * The few calls this application makes to TMDB's API: a movie by its IMDb
 * id, and a movie with its videos by its TMDB id. The key is a v3 API key,
 * sent as a parameter, or a v4 read access token, sent as a bearer token;
 * without one the client is off and makes no calls. A movie TMDB does not
 * have is null; a TMDB that cannot be reached throws [IOException], so the
 * caller stores the one and not the other.
 */
class TmdbClient(
    private val apiKey: String?,
    private val objectMapper: ObjectMapper,
    private val baseUrl: String = "https://api.themoviedb.org"
) {

    val enabled: Boolean = !apiKey.isNullOrBlank()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** The TMDB id of the movie with this IMDb id, or null when TMDB has no such movie. */
    fun findTmdbId(imdbId: String): Int? =
        get("/3/find/$imdbId?external_source=imdb_id")?.path("movie_results")?.firstOrNull()?.path("id")?.asInt()

    /** The movie with this TMDB id, with its IMDb id and its trailer, or null when TMDB has no such movie. */
    fun movie(tmdbId: Int): TmdbMovie? =
        get("/3/movie/$tmdbId?append_to_response=videos&include_video_language=en,null")?.let { json ->
            TmdbMovie(
                tmdbId = tmdbId,
                imdbId = json.path("imdb_id").asString(null)?.takeIf { it.startsWith("tt") },
                trailerKey = trailerOf(json.path("videos").path("results"))
            )
        }

    /**
     * The best of a movie's videos to show as its trailer: a YouTube video,
     * a trailer before a teaser, an official one before others, in English
     * before other languages, the latest first.
     */
    private fun trailerOf(videos: JsonNode): String? =
        videos.filter { it.path("site").asString("") == "YouTube" && it.path("type").asString("") in TRAILER_TYPES }
            .sortedWith(
                compareBy<JsonNode>(
                    { it.path("type").asString("") != "Trailer" },
                    { !it.path("official").asBoolean(false) },
                    { it.path("iso_639_1").asString("") != "en" }
                ).thenByDescending { it.path("published_at").asString("") }
            )
            .firstOrNull()?.path("key")?.asString(null)

    private fun get(pathAndQuery: String): JsonNode? {
        check(enabled) { "TMDB has no API key." }
        val bearer = apiKey!!.startsWith("eyJ") // a v4 read access token is a JWT
        val separator = if ('?' in pathAndQuery) '&' else '?'
        val uri = URI.create(if (bearer) baseUrl + pathAndQuery else "$baseUrl$pathAndQuery${separator}api_key=$apiKey")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $apiKey") }
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> objectMapper.readTree(response.body())
            404 -> null
            else -> throw IOException("TMDB answered ${response.statusCode()} for ${pathAndQuery.substringBefore('?')}")
        }
    }

    private companion object {
        val TRAILER_TYPES = setOf("Trailer", "Teaser")
    }
}
