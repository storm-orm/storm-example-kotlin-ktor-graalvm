package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.MovieService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.ktor.transactional

fun Route.movieRoutes(movieService: MovieService, watchlistService: WatchlistService, movieSeenService: MovieSeenService) {
    get("/movie/{movieId}") {
        val movieId = call.parameters.getOrFail("movieId")
        val detail = movieService.viewMovie(movieId, call.currentUser())
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown movie: $movieId")
        call.respondPage(ThymeleafContent("movie", mapOf("detail" to detail)))
    }

    // Route-scoped transactions: the exists/insert/remove cycle inside each
    // toggle runs atomically per request without any transaction code in the
    // services. The transaction opens before the handler, commits when it
    // completes, and rolls back when it throws. Both toggles answer with the
    // state of both lists, since marking a movie seen takes it off the watchlist.
    transactional {
        post("/api/watchlist/{movieId}") {
            val movieId = call.parameters.getOrFail("movieId")
            call.respond(watchlistService.toggle(call.currentUser(), movieId))
        }
        post("/api/seen/{movieId}") {
            val movieId = call.parameters.getOrFail("movieId")
            call.respond(movieSeenService.toggle(call.currentUser(), movieId))
        }
    }
}
