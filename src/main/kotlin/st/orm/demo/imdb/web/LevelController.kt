package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import st.orm.demo.imdb.model.Badge
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.BadgeService
import st.orm.demo.imdb.service.LevelService

/** The levels and the badges: the page that explains them, and the visitor's standing for the scripts that show it. */
fun Route.levelRoutes(levelService: LevelService, badgeService: BadgeService) {
    get("/play/levels") {
        val user = call.currentUser()
        call.respondPage(ThymeleafContent("levels", mapOf("levels" to levelService.levelsOf(user, call.levelProgress()), "badges" to badgeService.wallOf(user))))
    }

    // The star's pay link, pressed: the sponsorship is given, free of charge.
    post("/play/levels/sponsor") {
        badgeService.give(call.currentUser(), Badge.STAR_SPONSOR)
        call.respondRedirect("/play/levels")
    }

    // Where the visitor stands, after a check-in: the page tells them what it brought, badges included.
    get("/api/level") {
        val user = call.currentUser()
        call.respond(call.levelProgress().standing(badgeService.award(user).map { it.title }))
    }
}
