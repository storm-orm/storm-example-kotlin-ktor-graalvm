package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.PersonService

fun Route.personRoutes(personService: PersonService) {
    get("/person/{personId}") {
        val personId = call.parameters.getOrFail("personId")
        // The filmography by rating, or by year when asked.
        val byYear = call.request.queryParameters["sort"] == "year"
        val detail = personService.findPersonDetail(personId, byYear)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown person: $personId")
        call.respondPage(
            ThymeleafContent(
                "person",
                mapOf(
                    "person" to detail.person,
                    "filmography" to detail.filmography,
                    "byYear" to byYear,
                    "statistics" to detail.statistics
                )
            )
        )
    }
}
