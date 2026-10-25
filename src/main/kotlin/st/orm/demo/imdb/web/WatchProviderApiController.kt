package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.WatchProvider
import st.orm.demo.imdb.service.StreamingService
import st.orm.demo.imdb.service.TmdbService

/** A platform a movie plays on, as the movie page shows it: a logo, with the name for its tooltip. */
@Serializable
data class Platform(val name: String, val logoUrl: String?)

/**
 * Where a movie plays in the visitor's country, from TMDB (with JustWatch's
 * data), for the movie page to fetch after it loads, like the trailer. No
 * country, no platforms: the page renders fine without them.
 */
fun Route.watchProviderRoutes(tmdbService: TmdbService, streamingService: StreamingService) {
    get("/api/watch-providers/{movieId}") {
        val country = call.country() ?: return@get call.respond(emptyList<Platform>())
        val providers = tmdbService.watchProviders(call.parameters.getOrFail("movieId"), country.code, askTmdb = call.isSignedIn())
        call.respond(providers.map { it.toPlatform() })
    }

    // The signed-in visitor's services each of these movies streams on, by movie id, for the logos on the
    // posters of For You, What to watch next and the watchlist; a page asks after it loads, and again
    // for the cards its scroll adds.
    get("/api/my-services") {
        val country = call.country()?.takeIf { call.isSignedIn() } ?: return@get call.respond(emptyMap<String, List<Platform>>())
        val movieIds = call.request.queryParameters["movies"].orEmpty().split(',').filter { it.isNotBlank() }.take(MAX_SERVICE_MOVIES)
        val services = streamingService.servicesByMovie(call.currentUser(), country, movieIds)
        call.respond(services.mapValues { (_, providers) -> providers.map { it.toPlatform() } })
    }
}

/** The most posters one request asks the services of: For You kept to the services shows a hundred at most. */
private const val MAX_SERVICE_MOVIES = 100

/** A platform as a page shows it. */
fun WatchProvider.toPlatform() = Platform(name, logoPath?.let(::tmdbLogoUrl))

/** A logo on TMDB's image server, at a size that stays sharp for a small icon on a high-density screen. */
fun tmdbLogoUrl(path: String) = "https://image.tmdb.org/t/p/w92$path"
