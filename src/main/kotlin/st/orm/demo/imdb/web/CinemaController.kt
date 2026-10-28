package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import st.orm.demo.imdb.service.CinemaService

/** What plays in the visitor's country's cinemas: every movie, for the tags, and the ones for the home page's row. */
@Serializable
data class CinemaItems(val country: String?, val movies: List<MovieSearchItem>, val picks: List<MovieSearchItem>)

fun Route.cinemaRoutes(cinemaService: CinemaService) {
    // Fetched by the pages after they load, as the first ask of a day may wait on TMDB.
    get("/api/cinema") {
        val country = call.country()
        val now = cinemaService.nowPlaying(country, call.currentUser().movieFilter)
        call.respond(CinemaItems(country?.name, now.movies.map { it.toSearchItem() }, now.picks.map { it.toSearchItem() }))
    }
}
