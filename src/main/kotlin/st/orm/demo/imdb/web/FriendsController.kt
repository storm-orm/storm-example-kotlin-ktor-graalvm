package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.InviteState

/** A new invite link, for the friends page to share. */
@Serializable
data class InviteLink(val url: String)

/**
 * Friends need a signed-in account on both sides: the page asks a visitor
 * who is not to sign in, and returns them here. Inviting, accepting,
 * removing and the sharing settings are form posts and a small JSON call,
 * each answered with a redirect back to the page or the link to share.
 */
fun Route.friendsRoutes(friendService: FriendService) {
    get("/friends") {
        val user = call.currentUser()
        val view = if (user.name != null) friendService.friendsOf(user) else null
        call.respondPage(ThymeleafContent("friends", mapOf("view" to view, "added" to (call.request.queryParameters["added"] != null))))
    }

    // Invite links: a personal one for one friend, the public one to post anywhere, and a new public
    // one that replaces it. Each answers with the link, for the page to share.
    for ((path, create) in listOf<Pair<String, suspend (st.orm.demo.imdb.model.User) -> String>>(
        "/api/friends/invite" to { friendService.createInvite(it) },
        "/api/friends/public-invite" to { friendService.publicInvite(it) },
        "/api/friends/public-invite/new" to { friendService.replacePublicInvite(it) }
    )) {
        post(path) {
            val user = call.currentUser().takeIf { it.name != null }
                ?: return@post call.respond(HttpStatusCode.Forbidden)
            call.respond(InviteLink("${call.publicOrigin()}/friends/invite/${create(user)}"))
        }
    }

    get("/friends/invite/{token}") {
        val token = call.parameters.getOrFail("token")
        val user = call.currentUser()
        val state = friendService.invite(token, user)
        val model = mapOf(
            "token" to token,
            "inviter" to (state as? InviteState.Open)?.inviter,
            "own" to (state == InviteState.Own),
            "gone" to (state == InviteState.Gone)
        )
        call.respondPage(ThymeleafContent("invite", model))
    }

    post("/friends/invite/{token}") {
        val user = call.currentUser().takeIf { it.name != null }
            ?: return@post call.respondRedirect("/signin?rd=/friends/invite/" + call.parameters.getOrFail("token"))
        val accepted = friendService.accept(call.parameters.getOrFail("token"), user)
        call.respondRedirect(if (accepted) "/friends?added" else "/friends/invite/" + call.parameters.getOrFail("token"))
    }

    post("/friends/{friendId}/remove") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/friends")
        friendService.remove(user, call.parameters.getOrFail("friendId"))
        call.respondRedirect("/friends")
    }

    post("/friends/sharing") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/friends")
        val form: Parameters = call.receiveParameters()
        friendService.updateSharing(user, shareSeen = form["shareSeen"] != null, shareName = form["shareName"] != null, shareDates = form["shareDates"] != null)
        call.respondRedirect("/friends")
    }
}
