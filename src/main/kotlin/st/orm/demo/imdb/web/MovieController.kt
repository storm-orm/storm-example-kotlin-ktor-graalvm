package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.MovieService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.ktor.transactional

fun Route.movieRoutes(movieService: MovieService, watchlistService: WatchlistService) {
    get("/movie/{movieId}") {
        val movieId = call.parameters.getOrFail("movieId")
        val detail = movieService.viewMovie(movieId)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown movie: $movieId")
        call.respond(ThymeleafContent("movie", mapOf("detail" to detail)))
    }

    // Route-scoped transaction: the exists/insert/remove cycle inside toggle()
    // runs atomically per request without any transaction code in the service.
    // The transaction opens before the handler, commits when it completes, and
    // rolls back when it throws.
    transactional {
        post("/api/watchlist/{movieId}") {
            val movieId = call.parameters.getOrFail("movieId")
            call.respond(WatchlistState(onWatchlist = watchlistService.toggle(movieId)))
        }
    }
}
