package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import st.orm.demo.imdb.service.ListService
import st.orm.demo.imdb.service.ListView

/** Whether a movie is on a list, after a tap put it on or took it off. */
@Serializable
data class OnList(val onList: Boolean)

/**
 * Curated lists. Making and changing lists needs a signed-in account, as a
 * list carries its owner's name; a list of someone's own is public, for
 * anyone with its link, and unfurls into a card in a chat. The forms post
 * and redirect; the movie page's list menu is two small JSON calls.
 */
fun Route.listsRoutes(listService: ListService) {
    get("/lists") {
        val user = call.currentUser()
        if (user.name == null) return@get call.respondRedirect("/signin?rd=/lists")
        val parameters = call.request.queryParameters
        call.respondPage(ThymeleafContent("lists", mapOf(
            "view" to listService.listsOf(user),
            // Starting a list: from a movie's page, with the movie on it, or from a club's, for the club.
            "creating" to ("create" in parameters),
            "startMovie" to parameters["movie"]?.let { listService.movie(it) },
            "startClub" to parameters["club"]?.toLongOrNull()
        )))
    }

    post("/lists") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/signin?rd=/lists")
        val form = call.receiveParameters()
        val name = form["name"]?.trim().orEmpty()
        if (name.isEmpty()) return@post call.respondRedirect("/lists?create")
        val list = listService.create(user, name, form["description"], form["club"]?.toLongOrNull(), form["movie"]?.takeIf { it.isNotBlank() })
            ?: return@post call.respond(HttpStatusCode.Forbidden)
        call.respondRedirect("/lists/${list.id}")
    }

    get("/lists/{id}") {
        val view = listService.listOf(call.currentUser(), call.parameters.getOrFail<Long>("id"))
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondPage(ThymeleafContent("list", mapOf(
            "view" to view,
            "editing" to ("edit" in call.request.queryParameters && view.canManage),
            "share" to (if (view.list.club == null) call.listCard(view) else null)
        )))
    }

    post("/lists/{id}/edit") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/lists")
        val listId = call.parameters.getOrFail<Long>("id")
        val form = call.receiveParameters()
        form["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { listService.update(user, listId, it, form["description"]) }
        call.respondRedirect("/lists/$listId")
    }

    post("/lists/{id}/remove") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/lists")
        listService.remove(user, call.parameters.getOrFail<Long>("id"))
        call.respondRedirect("/lists")
    }

    // The movie page's list menu: the visitor's lists, ticked where the movie is on them, and a tap on one.
    get("/api/lists/choices") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(listService.choicesFor(user, call.request.queryParameters.getOrFail("movie")))
    }
    post("/api/lists/{id}/movies/{movieId}") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respond(HttpStatusCode.Forbidden)
        val onList = listService.toggle(user, call.parameters.getOrFail<Long>("id"), call.parameters.getOrFail("movieId"))
            ?: return@post call.respond(HttpStatusCode.Forbidden)
        call.respond(OnList(onList))
    }
}

/** A public list unfurls into its name and owner, with the poster of its first movie. */
private fun ApplicationCall.listCard(view: ListView): ShareCard {
    val origin = publicOrigin()
    val count = view.items.size
    return ShareCard(
        title = view.list.name,
        description = listOfNotNull(
            "A list by ${view.list.owner.name}",
            if (count == 1) "1 movie" else "$count movies",
            view.list.description
        ).joinToString(" · "),
        imageUrl = view.items.firstOrNull()?.let { "$origin/api/poster/${it.movie.id}" } ?: "$origin/img/invite-card.jpg",
        url = "$origin/lists/${view.list.id}",
        type = "website"
    )
}
