package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.MovieDetail
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.MovieService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.ktor.transactional
import java.time.LocalDate

/** The query parameter a recommendation surface adds to its links, so the visit counts as one that came of it. */
private const val VIA_FOR_YOU = "for-you"

/**
 * What a share of the movie carries: the message the share button sends,
 * and the card the shared link unfurls into in a chat or a timeline (the
 * Open Graph tags in the head). The URLs are absolute, as a card asks, and
 * the link is the page's own, without the parameter a recommendation adds.
 */
data class ShareCard(
    val title: String,
    val description: String,
    val imageUrl: String,
    val url: String
)

private fun ApplicationCall.shareCard(detail: MovieDetail): ShareCard {
    val origin = publicOrigin()
    val year = detail.movie.startYear?.let { " ($it)" } ?: ""
    return ShareCard(
        title = "${detail.movie.primaryTitle}$year",
        description = detail.caption,
        imageUrl = "$origin/api/poster/${detail.movie.id}",
        url = "$origin/movie/${detail.movie.id}"
    )
}

fun Route.movieRoutes(movieService: MovieService, watchlistService: WatchlistService, movieSeenService: MovieSeenService) {
    get("/movie/{movieId}") {
        val movieId = call.parameters.getOrFail("movieId")
        val fromRecommendation = call.request.queryParameters["via"] == VIA_FOR_YOU
        val detail = movieService.viewMovie(movieId, call.currentUser(), call.isReturningVisitor(), fromRecommendation)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown movie: $movieId")
        call.respondPage(ThymeleafContent("movie", mapOf("detail" to detail, "share" to call.shareCard(detail))))
    }

    // Route-scoped transactions: the exists/insert/remove cycle inside each
    // toggle runs atomically per request without any transaction code in the
    // services. The transaction opens before the handler, commits when it
    // completes, and rolls back when it throws. Every route answers with the
    // state of both lists, since marking a movie seen takes it off the watchlist.
    transactional {
        post("/api/watchlist/{movieId}") {
            val movieId = call.parameters.getOrFail("movieId")
            call.respond(watchlistService.toggle(call.currentUser(), movieId))
        }
        // Marks or unmarks a movie as seen: as a check-in today, or with ?when=earlier as history.
        post("/api/seen/{movieId}") {
            val movieId = call.parameters.getOrFail("movieId")
            val historic = call.request.queryParameters["when"] == "earlier"
            call.respond(movieSeenService.toggle(call.currentUser(), movieId, historic))
        }
        // Checks a movie in, today or with ?day=2026-10-22 on a day of the past week; seen before or not.
        post("/api/seen/{movieId}/check-in") {
            val day = call.request.queryParameters["day"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            try {
                call.respond(movieSeenService.checkIn(call.currentUser(), call.parameters.getOrFail("movieId"), day))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message.orEmpty())
            }
        }
        post("/api/seen/{movieId}/vote") {
            val movieId = call.parameters.getOrFail("movieId")
            val vote = call.receive<Vote>()
            call.respond(movieSeenService.vote(call.currentUser(), movieId, vote.liked))
        }
    }
}
