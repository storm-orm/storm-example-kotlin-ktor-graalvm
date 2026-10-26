package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.WatchProvider
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A movie as TMDB has it: its TMDB id, its IMDb id if TMDB knows it, the
 * YouTube key of its trailer, if any, and per country the platforms it plays on.
 */
data class TmdbMovie(
    val tmdbId: Int,
    val imdbId: String?,
    val trailerKey: String?,
    val watchProviders: Map<String, List<WatchProvider>>
)

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

    /**
     * The language the movie with this IMDb id was originally made in, as
     * TMDB has it: an ISO 639 code, or null when TMDB has no such movie or
     * says it has no language. TMDB names Cantonese "cn", which is "yue".
     */
    fun findOriginalLanguage(imdbId: String): String? =
        get("/3/find/$imdbId?external_source=imdb_id")?.path("movie_results")?.firstOrNull()
            ?.path("original_language")?.asString(null)
            ?.takeUnless { it.isEmpty() || it == NO_LANGUAGE }
            ?.let { if (it == CANTONESE) "yue" else it }

    /**
     * The movie with this TMDB id, with its IMDb id, its trailer and the
     * platforms it plays on, in one call, or null when TMDB has no such movie.
     */
    fun movie(tmdbId: Int): TmdbMovie? =
        get("/3/movie/$tmdbId?append_to_response=videos,watch/providers&include_video_language=en,null")?.let { json ->
            TmdbMovie(
                tmdbId = tmdbId,
                imdbId = json.path("imdb_id").asString(null)?.takeIf { it.startsWith("tt") },
                trailerKey = trailerOf(json.path("videos").path("results")),
                watchProviders = watchProvidersOf(json.path("watch/providers").path("results"))
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

    /**
     * Per country, the platforms that stream a movie or show it free, in
     * TMDB's order: by subscription first, then free and with ads. Renting
     * and buying are left out, and so is a country where it plays nowhere.
     * A service resold through another ("HBO Max Amazon Channel") and a
     * service's plan with ads ("Netflix Standard with Ads") are left out
     * too: the service itself is what to show.
     */
    private fun watchProvidersOf(countries: JsonNode): Map<String, List<WatchProvider>> =
        countries.properties().associate { (country, offers) ->
            country to OFFER_TYPES
                .flatMap { type -> offers.path(type).sortedBy { it.path("display_priority").asInt() } }
                .map(::watchProviderOf)
                .filter(::isOwnService)
                .distinctBy { it.id }
        }.filterValues { it.isNotEmpty() }

    /**
     * The streaming services active in a country (ISO 3166-1 alpha-2), the
     * most watched there first, for the account page to choose from; as
     * with a movie's platforms, the resold ones and the plans with ads are
     * left out.
     */
    fun watchProvidersIn(country: String): List<WatchProvider> =
        get("/3/watch/providers/movie?watch_region=$country")?.path("results")?.toList().orEmpty()
            .sortedBy { it.path("display_priorities").path(country).asInt(it.path("display_priority").asInt()) }
            .map(::watchProviderOf)
            .filter(::isOwnService)

    /**
     * The TMDB ids of the movies in a country's cinemas now (ISO 3166-1
     * alpha-2), the most popular first: the first [pages] pages of twenty.
     */
    fun nowPlaying(country: String, pages: Int = NOW_PLAYING_PAGES): List<Int> =
        (1..pages).flatMap { page ->
            get("/3/movie/now_playing?region=$country&page=$page")?.path("results")?.map { it.path("id").asInt() }.orEmpty()
        }.distinct()

    private fun watchProviderOf(json: JsonNode) =
        WatchProvider(json.path("provider_id").asInt(), json.path("provider_name").asString(""), json.path("logo_path").asString(null))

    /** Whether TMDB names a service itself, rather than one resold through another or a plan with ads. */
    private fun isOwnService(provider: WatchProvider) =
        !provider.name.endsWith(" Channel") && !provider.name.endsWith(" with Ads")

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
        val OFFER_TYPES = listOf("flatrate", "free", "ads")
        const val NO_LANGUAGE = "xx"
        const val CANTONESE = "cn"
        const val NOW_PLAYING_PAGES = 3
    }
}
