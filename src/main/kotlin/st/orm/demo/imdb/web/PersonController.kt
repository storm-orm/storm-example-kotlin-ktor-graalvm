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
        val detail = personService.findPersonDetail(personId)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown person: $personId")
        call.respond(
            ThymeleafContent(
                "person",
                mapOf(
                    "person" to detail.person,
                    "filmography" to detail.filmography,
                    "statistics" to detail.statistics
                )
            )
        )
    }
}
