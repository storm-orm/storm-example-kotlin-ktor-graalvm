package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.HomeService

fun Route.homeRoutes(homeService: HomeService) {
    get("/") {
        call.respondPage(ThymeleafContent("home", mapOf(
            "view" to homeService.buildHomeView(call.currentUser(), call.country()),
            // Whether the visitor has been to the catch-up page: the page nudges them there until they have.
            "catchUpSeen" to (call.request.cookies[CATCH_UP_COOKIE] != null)
        )))
    }
}
