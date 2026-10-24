package st.orm.demo.imdb.web

import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.Accepted
import st.orm.demo.imdb.service.InviteService
import st.orm.demo.imdb.service.InviteState

/**
 * The invite page, for both kinds of invite: to be friends, or to join a
 * club. The page names who invites and to what; accepting needs a
 * signed-in account, and takes the visitor to their friends or to the club.
 */
fun Route.inviteRoutes(inviteService: InviteService) {
    get("/invite/{token}") {
        val token = call.parameters.getOrFail("token")
        val state = inviteService.invite(token, call.currentUser())
        val model = mapOf(
            "token" to token,
            "befriend" to (state as? InviteState.Befriend),
            "join" to (state as? InviteState.Join),
            "member" to (state as? InviteState.AlreadyMember),
            "own" to (state == InviteState.Own),
            "full" to (state == InviteState.Full),
            "gone" to (state == InviteState.Gone)
        )
        call.respondPage(ThymeleafContent("invite", model))
    }

    post("/invite/{token}") {
        val token = call.parameters.getOrFail("token")
        val user = call.currentUser().takeIf { it.name != null }
            ?: return@post call.respondRedirect("/signin?rd=/invite/$token")
        call.respondRedirect(
            when (val accepted = inviteService.accept(token, user)) {
                null -> "/invite/$token"
                Accepted.Friends -> "/friends?added"
                is Accepted.Joined -> "/clubs/${accepted.club.id}?joined"
            }
        )
    }
}
