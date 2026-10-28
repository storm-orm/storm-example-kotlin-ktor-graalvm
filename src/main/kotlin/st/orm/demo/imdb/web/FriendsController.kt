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
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.service.FriendService

/** A new invite link, for the friends page or a club's page to share. */
@Serializable
data class InviteLink(val url: String)

/** The link of an invite of either kind: one page shows both. */
fun inviteUrl(origin: String, token: String) = "$origin/invite/$token"

/**
 * Friends need a signed-in account on both sides. Inviting, the requests
 * between club members, removing and the sharing settings are form posts
 * and a small JSON call, each answered with a redirect back to the
 * community page or the link to share; the invite page itself is
 * [inviteRoutes], and the page that lists the friends [communityRoutes].
 */
fun Route.friendsRoutes(friendService: FriendService) {
    // Invite links: a personal one for one friend, the public one to post anywhere, and a new public
    // one that replaces it. Each answers with the link, for the page to share.
    for ((path, create) in listOf<Pair<String, suspend (User) -> String>>(
        "/api/friends/invite" to { friendService.createInvite(it) },
        "/api/friends/public-invite" to { friendService.publicInvite(it) },
        "/api/friends/public-invite/new" to { friendService.replacePublicInvite(it) }
    )) {
        post(path) {
            val user = call.currentUser().takeIf { it.name != null }
                ?: return@post call.respond(HttpStatusCode.Forbidden)
            call.respond(InviteLink(inviteUrl(call.publicOrigin(), create(user))))
        }
    }

    // Withdrawing a personal invite link: it stops working.
    post("/friends/invites/{token}/withdraw") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
        friendService.withdrawInvite(user, call.parameters.getOrFail("token"))
        call.respondRedirect("/community?manage#friends-card")
    }

    // The friend invite links made before invites of both kinds shared one page lead there.
    get("/friends/invite/{token}") {
        call.respondRedirect("/invite/" + call.parameters.getOrFail("token"))
    }

    // Friend requests between club members: asking and withdrawing are on the club's page, answering
    // on the friends page and the club's. Each goes back to the club's page when it names a club.
    fun requestPost(path: String, action: suspend (user: User, userId: String, clubId: Long?) -> Unit) {
        post(path) {
            val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
            val clubId = call.receiveParameters()["clubId"]?.toLongOrNull()
            action(user, call.parameters.getOrFail("userId"), clubId)
            call.respondRedirect(clubId?.let { "/clubs/$it" } ?: "/community")
        }
    }
    requestPost("/friends/requests/{userId}") { user, userId, clubId -> clubId?.let { friendService.request(user, userId, it) } }
    requestPost("/friends/requests/{userId}/withdraw") { user, userId, _ -> friendService.withdraw(user, userId) }
    requestPost("/friends/requests/{userId}/accept") { user, userId, _ -> friendService.accept(user, userId) }
    requestPost("/friends/requests/{userId}/dismiss") { user, userId, _ -> friendService.dismiss(user, userId) }

    post("/friends/{friendId}/remove") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
        friendService.remove(user, call.parameters.getOrFail("friendId"))
        call.respondRedirect("/community?manage")
    }

    post("/friends/sharing") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
        val form: Parameters = call.receiveParameters()
        friendService.updateSharing(user, shareSeen = form["shareSeen"] != null, shareName = form["shareName"] != null, shareDates = form["shareDates"] != null)
        call.respondRedirect("/community?manage")
    }
}
