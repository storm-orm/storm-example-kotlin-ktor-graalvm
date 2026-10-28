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
import st.orm.demo.imdb.model.NightMethod
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.service.MovieNightService
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.Planned
import java.time.LocalDate

/**
 * A club's movie nights, for its members: the form to plan one, and the
 * night's page, where the members vote and the host picks the winner. All
 * of it is form posts, answered with a redirect to the night.
 */
fun Route.movieNightRoutes(movieNightService: MovieNightService, movieSeenService: MovieSeenService) {
    get("/clubs/{id}/nights/new") {
        val clubId = call.parameters.getOrFail<Long>("id")
        val user = call.currentUser()
        if (user.name == null) return@get call.respondRedirect("/signin?rd=/clubs/$clubId/nights/new")
        val plan = movieNightService.planFor(user, clubId, call.country()) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondPage(ThymeleafContent("night-plan", mapOf(
            "plan" to plan,
            "nothingFound" to ("nothing" in call.request.queryParameters)
        )))
    }

    post("/clubs/{id}/nights") {
        val clubId = call.parameters.getOrFail<Long>("id")
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
        val form = call.receiveParameters()
        val day = form["day"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val planned = movieNightService.plan(
            user = user,
            clubId = clubId,
            title = form["title"].orEmpty(),
            day = day,
            // At the cinema, or at home on the services ticked.
            serviceIds = if (form["where"] == "cinema") emptySet() else form.getAll("service").orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
            cinema = form["where"] == "cinema",
            listId = form["list"]?.toLongOrNull(),
            country = call.country()?.code,
            // A vote on a few, or a swipe round of the club on a deck.
            method = if (form["method"] == "swipe") NightMethod.SWIPE else NightMethod.VOTE
        )
        when (planned) {
            is Planned.Night -> call.respondRedirect("/nights/${planned.night.id}")
            Planned.NothingFound -> call.respondRedirect("/clubs/$clubId/nights/new?nothing")
            Planned.NotAllowed -> call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/nights/{id}") {
        val nightId = call.parameters.getOrFail<Long>("id")
        val user = call.currentUser()
        if (user.name == null) return@get call.respondRedirect("/signin?rd=/nights/$nightId")
        val view = movieNightService.nightOf(user, nightId) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondPage(ThymeleafContent("night", mapOf("view" to view)))
    }

    /** A form post on a night by a signed-in member: the action, then back to the night, or wherever it says. */
    fun nightPost(path: String, action: suspend RoutingContext.(user: User, nightId: Long) -> String?) {
        post(path) {
            val nightId = call.parameters.getOrFail<Long>("id")
            val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/community")
            call.respondRedirect(action(user, nightId) ?: "/nights/$nightId")
        }
    }

    nightPost("/nights/{id}/vote/{movieId}") { user, nightId ->
        movieNightService.vote(user, nightId, call.parameters.getOrFail("movieId"))
        null
    }
    nightPost("/nights/{id}/decide") { user, nightId ->
        movieNightService.decide(user, nightId)
        null
    }
    nightPost("/nights/{id}/reopen") { user, nightId ->
        movieNightService.decide(user, nightId, reopen = true)
        null
    }
    nightPost("/nights/{id}/remove") { user, nightId ->
        movieNightService.remove(user, nightId)?.let { "/clubs/$it" }
    }
    // The night come, a member checks the movie in on its day.
    nightPost("/nights/{id}/check-in") { user, nightId ->
        val view = movieNightService.nightOf(user, nightId)
        val winner = view?.night?.winner
        if (view != null && winner != null && view.canCheckIn) movieSeenService.checkIn(user, winner.id, view.night.nightOn)
        null
    }
}
