package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.WatchProvider
import st.orm.demo.imdb.service.TmdbService

/** A platform a movie plays on, as the movie page shows it: a logo, with the name for its tooltip. */
@Serializable
data class Platform(val name: String, val logoUrl: String?)

/**
 * Where a movie plays in the visitor's country, from TMDB (with JustWatch's
 * data), for the movie page to fetch after it loads, like the trailer. No
 * country, no platforms: the page renders fine without them.
 */
fun Route.watchProviderRoutes(tmdbService: TmdbService) {
    get("/api/watch-providers/{movieId}") {
        val country = call.country() ?: return@get call.respond(emptyList<Platform>())
        val providers = tmdbService.watchProviders(call.parameters.getOrFail("movieId"), country.code, askTmdb = call.isSignedIn())
        call.respond(providers.map { it.toPlatform() })
    }
}

/** A platform as a page shows it. */
fun WatchProvider.toPlatform() = Platform(name, logoPath?.let(::tmdbLogoUrl))

/** A logo on TMDB's image server, at a size that stays sharp for a small icon on a high-density screen. */
fun tmdbLogoUrl(path: String) = "https://image.tmdb.org/t/p/w92$path"
