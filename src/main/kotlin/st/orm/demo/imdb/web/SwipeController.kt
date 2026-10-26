package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.model.SwipeWay
import st.orm.demo.imdb.service.SwipeService
import st.orm.ktor.transactional

/**
 * The deck on one's own: the page with its stack of posters, and each
 * swipe, posted by the page as it goes. A swipe does what a movie page's
 * buttons do, so it runs in one transaction, as those do.
 */
fun Route.swipeRoutes(swipeService: SwipeService) {
    get("/swipe") {
        call.respondPage(ThymeleafContent("swipe", mapOf("swipe" to swipeService.deckOf(call.currentUser()))))
    }

    transactional {
        post("/api/swipe") {
            val form = call.receiveParameters()
            val way = form["way"]?.let { runCatching { SwipeWay.valueOf(it) }.getOrNull() } ?: return@post call.respond(HttpStatusCode.BadRequest)
            swipeService.swipe(call.currentUser(), form.getOrFail("movie"), way)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
