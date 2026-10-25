package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import kotlinx.serialization.Serializable
import st.orm.demo.imdb.service.InsightsService
import st.orm.demo.imdb.service.StreamingService

/** A service the visitor has, as the About You page draws it: its logo and name, its watched movies and share. */
@Serializable
data class ServiceShareItem(val name: String, val logoUrl: String?, val movies: Int, val percent: Int)

/** The share of the visitor's services, and how far TMDB has told where their watched movies stream. */
@Serializable
data class ServiceSharesView(val services: List<ServiceShareItem>, val counted: Int, val known: Int, val watched: Int)

fun Route.insightsRoutes(insightsService: InsightsService, streamingService: StreamingService) {
    get("/insights") {
        call.respondPage(ThymeleafContent("insights", mapOf("insights" to insightsService.insightsFor(call.currentUser()))))
    }

    // The share of the signed-in visitor's services among the movies they watched, for the page to fetch
    // after it loads, and again while not every watched movie is known (StreamingService.sharesOfWatched).
    get("/api/insights/services") {
        val country = call.country()?.takeIf { call.isSignedIn() } ?: return@get call.respond(HttpStatusCode.NotFound)
        val shares = streamingService.sharesOfWatched(call.currentUser(), country)
        call.respond(
            ServiceSharesView(
                services = shares.shares.map { ServiceShareItem(it.service.name, it.service.logoPath?.let(::tmdbLogoUrl), it.movies, it.percent) },
                counted = shares.counted,
                known = shares.known,
                watched = shares.watched
            )
        )
    }
}
