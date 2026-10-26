package st.orm.demo.imdb.web

import io.ktor.http.encodeURLParameter
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.SixDegreesService

/**
 * Six degrees: two actors picked on the page, or at random, and the chain
 * of movies between them. The pair is in the address, so a chain is a link
 * to share.
 */
fun Route.sixDegreesRoutes(sixDegreesService: SixDegreesService) {
    get("/six-degrees") {
        val parameters = call.request.queryParameters
        val from = parameters["from"]?.takeIf { it.isNotBlank() }
        val to = parameters["to"]?.takeIf { it.isNotBlank() }
        val connection = if (from != null && to != null) sixDegreesService.connect(from, to) else null
        // One of the two given, as a person page links here, fills in its box.
        val start = connection?.from ?: from?.let { sixDegreesService.person(it) }
        call.respondPage(ThymeleafContent("six-degrees", mapOf(
            "connection" to connection,
            "start" to start,
            "asked" to (from != null && to != null),
            "minimumVotes" to sixDegreesService.minimumVotes
        )))
    }

    get("/six-degrees/surprise") {
        val (from, to) = sixDegreesService.randomPair(call.currentUser().movieFilter)
        call.respondRedirect("/six-degrees?from=${from.encodeURLParameter()}&to=${to.encodeURLParameter()}")
    }
}
