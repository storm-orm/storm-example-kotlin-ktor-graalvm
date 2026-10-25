package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.RecommendationService
import st.orm.demo.imdb.service.StreamingService
import st.orm.ktor.transactional

/** The query parameter that keeps For You to the picks on the visitor's streaming services. */
private const val ON_SERVICES = "services"

fun Route.forYouRoutes(recommendationService: RecommendationService, streamingService: StreamingService) {
    get("/for-you") {
        val user = call.currentUser()
        val country = call.country()
        val hasServices = user.name != null && country != null && streamingService.servicesOf(user).isNotEmpty()
        val onServices = hasServices && call.request.queryParameters["on"] == ON_SERVICES
        val model = if (onServices && country != null) {
            // Kept to the visitor's services: all the picks at once, a hundred at most, without the scroll.
            val picks = streamingService.keepToServices(user, country, recommendationService.allPicks(user))
            mapOf("personal" to true, "newArrivals" to emptyList<Any>(), "newSinceDays" to 0L, "movies" to picks, "nextCursor" to null)
        } else {
            val view = recommendationService.forYou(user)
            mapOf(
                "personal" to view.personal,
                "newArrivals" to view.newArrivals,
                "newSinceDays" to view.newSince.toDays(),
                "movies" to view.movies,
                "nextCursor" to view.nextCursor
            )
        }
        call.respondPage(ThymeleafContent("for-you", model + mapOf("onServices" to onServices)))
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
