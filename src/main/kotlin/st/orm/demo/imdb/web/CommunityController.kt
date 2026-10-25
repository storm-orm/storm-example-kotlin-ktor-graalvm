package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.CommunityService

/** The most movies in common one call lists. */
private const val IN_COMMON_LIMIT = 24

/**
 * The community page: the visitor's friends and clubs as one. Signed in
 * with a community, it opens on what they watched and what the visitor has
 * in common with them; the friends, the clubs, the invites and the sharing
 * are behind its cog (`?manage`), and are the page itself without a
 * community yet. The friends and clubs pages it replaced redirect here.
 */
fun Route.communityRoutes(communityService: CommunityService) {
    get("/community") {
        val user = call.currentUser()
        val parameters = call.request.queryParameters
        val view = if (user.name != null) communityService.overview(user) else null
        val manage = view == null || !view.hasCommunity || "manage" in parameters
        call.respondPage(ThymeleafContent("community", mapOf(
            "view" to view,
            "manage" to manage,
            "added" to ("added" in parameters),
            "closed" to ("closed" in parameters)
        )))
    }

    // The movies the visitor and someone in their community both watched, for the row that unfolds under them.
    get("/api/community/in-common/{userId}") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@get call.respond(HttpStatusCode.Forbidden)
        val movies = communityService.moviesInCommon(user, call.parameters.getOrFail("userId"), IN_COMMON_LIMIT)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(SearchWindow(movies.map { it.toSearchItem() }, null))
    }

    get("/friends") {
        call.respondRedirect(if (call.request.queryParameters["tab"] == "manage") "/community?manage" else "/community", permanent = true)
    }
    get("/clubs") {
        call.respondRedirect("/community?manage", permanent = true)
    }
}
