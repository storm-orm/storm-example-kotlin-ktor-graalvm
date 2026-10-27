package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.BadgeService
import st.orm.demo.imdb.service.LevelService

/** The levels and the badges: the page that explains them, and the visitor's standing for the scripts that show it. */
fun Route.levelRoutes(levelService: LevelService, badgeService: BadgeService) {
    get("/play/levels") {
        val user = call.currentUser()
        call.respondPage(ThymeleafContent("levels", mapOf("levels" to levelService.levelsOf(user), "badges" to badgeService.wallOf(user))))
    }

    // Where the visitor stands, after a check-in: the page tells them what it brought, badges included.
    get("/api/level") {
        val user = call.currentUser()
        call.respond(levelService.progressOf(user).standing(badgeService.award(user).map { it.title }))
    }
}
