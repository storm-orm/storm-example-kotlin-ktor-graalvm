package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.HomeService

fun Route.homeRoutes(homeService: HomeService) {
    get("/") {
        call.respond(ThymeleafContent("home", mapOf("view" to homeService.buildHomeView())))
    }
}
