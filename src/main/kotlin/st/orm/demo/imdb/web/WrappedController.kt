package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.WrappedService
import st.orm.demo.imdb.service.YearInMovies
import java.time.LocalDate
import java.time.ZoneOffset

/** The first year a check-in can fall in; IMDb exports reach back to the nineties at most. */
private const val FIRST_YEAR = 1990

/**
 * A year in movies: the visitor's own, of this year or an earlier one, and a
 * year someone shared, which anyone with its link sees, as a card in a chat.
 * Sharing needs a signed-in account, as the shared page names its owner.
 */
fun Route.wrappedRoutes(wrappedService: WrappedService) {
    get("/wrapped") {
        call.respondYear(wrappedService.yearOf(call.currentUser(), LocalDate.now(ZoneOffset.UTC).year))
    }

    get("/wrapped/{year}") {
        val year = call.parameters["year"]?.toIntOrNull()?.takeIf { it in FIRST_YEAR..LocalDate.now(ZoneOffset.UTC).year }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondYear(wrappedService.yearOf(call.currentUser(), year))
    }

    get("/wrapped/shared/{token}") {
        val year = wrappedService.shared(call.parameters.getOrFail("token"))
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondYear(year, call.yearCard(year, call.parameters.getOrFail("token")))
    }

    // The link to share the year with, made the first time it is shared; the page hands it to the share sheet.
    post("/api/wrapped/{year}/share") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respond(HttpStatusCode.Forbidden)
        val year = call.parameters.getOrFail<Int>("year")
        call.respond(InviteLink("${call.publicOrigin()}/wrapped/shared/${wrappedService.share(user, year)}"))
    }
}

/** The year's page: the visitor's own, or, with a [card], one shared with them. */
private suspend fun ApplicationCall.respondYear(year: YearInMovies, card: ShareCard? = null) {
    respondPage(ThymeleafContent("wrapped", mapOf(
        "view" to year,
        "shared" to (card != null),
        "share" to card
    )))
}

/** A shared year unfurls into its owner's year, with the poster of the first standout. */
private fun ApplicationCall.yearCard(year: YearInMovies, token: String): ShareCard {
    val origin = publicOrigin()
    val name = year.owner.name ?: "Someone"
    val poster = (year.standouts.firstOrNull()?.movie ?: year.posters.firstOrNull())?.let { "$origin/api/poster/${it.id}" }
    return ShareCard(
        title = "$name's ${year.year} in movies",
        description = year.caption,
        imageUrl = poster ?: "$origin/img/invite-card.jpg",
        url = "$origin/wrapped/shared/$token",
        type = "website"
    )
}
