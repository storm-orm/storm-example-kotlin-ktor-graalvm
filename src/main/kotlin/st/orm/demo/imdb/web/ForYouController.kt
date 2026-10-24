package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.RecommendationService
import st.orm.ktor.transactional

fun Route.forYouRoutes(recommendationService: RecommendationService) {
    get("/for-you") {
        val view = recommendationService.forYou(call.currentUser())
        call.respondPage(
            ThymeleafContent(
                "for-you",
                mapOf(
                    "personal" to view.personal,
                    "newArrivals" to view.newArrivals,
                    "newSinceDays" to view.newSince.toDays(),
                    "movies" to view.movies,
                    "nextCursor" to view.nextCursor
                )
            )
        )
    }

    // The next window of the visitor's picks, same cursor contract as the other scrolls.
    get("/api/for-you") {
        val window = recommendationService.scroll(call.currentUser(), call.request.queryParameters.getOrFail("cursor"))
        call.respond(SearchWindow(window.content().map { it.toRecommendedItem() }, window.nextCursor()))
    }

    // "Not interested": the dismissal, the pick's removal and the event are one transaction.
    transactional {
        post("/api/for-you/{movieId}/dismiss") {
            recommendationService.dismiss(call.currentUser(), call.parameters.getOrFail("movieId"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
