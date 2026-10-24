package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import st.orm.demo.imdb.service.TmdbService

/** A movie's trailer: the YouTube video to play. */
@Serializable
data class Trailer(val youtubeKey: String)

/**
 * The trailer of a movie, from TMDB, for the movie page and the featured
 * movie to fetch after they load; they show the Trailer button only once it
 * answers, and render fine without it.
 */
fun Route.trailerRoutes(tmdbService: TmdbService) {
    get("/api/trailer/{movieId}") {
        val key = tmdbService.trailer(call.parameters.getOrFail("movieId"))
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(Trailer(key))
    }
}
