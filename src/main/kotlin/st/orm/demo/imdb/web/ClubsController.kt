package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.service.ClubService
import st.orm.demo.imdb.service.Leaving
import st.orm.demo.imdb.service.ListService

/**
 * Clubs need a signed-in account, as friends do: the pages ask a visitor who
 * is not to sign in. A club's page is for its members only. Starting a club,
 * managing its members, leaving and the sharing settings are form posts,
 * answered with a redirect; the invite links, for admins, a small JSON call
 * answered with the link to share.
 */
fun Route.clubsRoutes(clubService: ClubService, listService: ListService) {
    post("/clubs") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
        val name = call.receiveParameters()["name"]?.trim().orEmpty()
        if (name.isEmpty()) return@post call.respondRedirect("/community?manage")
        call.respondRedirect("/clubs/${clubService.create(user, name).id}")
    }

    post("/clubs/sharing") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
        val form = call.receiveParameters()
        clubService.updateDefaults(user, shareSeen = form["shareSeen"] != null, shareName = form["shareName"] != null, shareDates = form["shareDates"] != null)
        call.respondRedirect("/community?manage")
    }

    get("/clubs/{id}") {
        val user = call.currentUser()
        if (user.name == null) return@get call.respondRedirect("/signin?rd=/clubs/" + call.parameters.getOrFail("id"))
        val view = clubService.clubOf(user, call.parameters.getOrFail<Long>("id"))
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val parameters = call.request.queryParameters
        call.respondPage(ThymeleafContent("club", mapOf(
            "view" to view,
            "lists" to listService.listsOf(view.club),
            "tab" to (if (parameters["tab"] == "manage") "manage" else "club"),
            "joined" to (parameters["joined"] != null),
            "lastAdmin" to (parameters["last-admin"] != null)
        )))
    }

    /** A form post on a club of the signed-in visitor: the action, then back to the club's page, or to the clubs page without an account. */
    fun clubPost(path: String, action: suspend RoutingContext.(user: User, clubId: Long) -> String?) {
        post(path) {
            val clubId = call.parameters.getOrFail<Long>("id")
            val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
            call.respondRedirect(action(user, clubId) ?: "/clubs/$clubId")
        }
    }

    clubPost("/clubs/{id}/rename") { user, clubId ->
        call.receiveParameters()["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { clubService.rename(user, clubId, it) }
        null
    }
    clubPost("/clubs/{id}/members/{userId}/admin") { user, clubId ->
        val admin = call.receiveParameters()["admin"] == "true"
        if (clubService.setAdmin(user, clubId, call.parameters.getOrFail("userId"), admin)) null else "/clubs/$clubId?last-admin"
    }
    clubPost("/clubs/{id}/members/{userId}/remove") { user, clubId ->
        clubService.remove(user, clubId, call.parameters.getOrFail("userId"))
        null
    }
    clubPost("/clubs/{id}/leave") { user, clubId ->
        when (clubService.leave(user, clubId)) {
            Leaving.LEFT -> "/community?manage"
            Leaving.CLOSED -> "/community?manage&closed"
            Leaving.LAST_ADMIN -> "/clubs/$clubId?last-admin"
        }
    }
    clubPost("/clubs/{id}/sharing") { user, clubId ->
        val form = call.receiveParameters()
        if (form["defaults"] != null) {
            clubService.followDefaults(user, clubId)
        } else {
            clubService.updateSharing(user, clubId, shareSeen = form["shareSeen"] != null, shareName = form["shareName"] != null, shareDates = form["shareDates"] != null)
        }
        null
    }

    clubPost("/clubs/{id}/invites/{token}/withdraw") { user, clubId ->
        clubService.withdrawInvite(user, clubId, call.parameters.getOrFail("token"))
        null
    }

    // Invite links, for the club's admins: a personal one for one person, the club's public one, and a
    // new public one that replaces it. Each answers with the link, for the page to share.
    for ((path, create) in listOf<Pair<String, suspend (User, Long) -> String?>>(
        "/api/clubs/{id}/invite" to { user, clubId -> clubService.createInvite(user, clubId) },
        "/api/clubs/{id}/public-invite" to { user, clubId -> clubService.publicInvite(user, clubId) },
        "/api/clubs/{id}/public-invite/new" to { user, clubId -> clubService.replacePublicInvite(user, clubId) }
    )) {
        post(path) {
            val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val token = create(user, call.parameters.getOrFail<Long>("id")) ?: return@post call.respond(HttpStatusCode.Forbidden)
            call.respond(InviteLink(inviteUrl(call.publicOrigin(), token)))
        }
    }
}
