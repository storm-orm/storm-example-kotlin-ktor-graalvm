package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.LevelService

/** The levels: the page that explains them, and the visitor's standing for the scripts that show it. */
fun Route.levelRoutes(levelService: LevelService) {
    get("/play/levels") {
        call.respondPage(ThymeleafContent("levels", mapOf("levels" to levelService.levelsOf(call.currentUser()))))
    }

    // Where the visitor stands, after a check-in: the page tells them what it brought.
    get("/api/level") {
        call.respond(levelService.progressOf(call.currentUser()).standing())
    }
}
