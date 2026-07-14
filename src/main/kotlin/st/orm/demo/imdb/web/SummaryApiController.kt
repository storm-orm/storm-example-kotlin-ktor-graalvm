package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import st.orm.demo.imdb.service.WikipediaSummaryService

/**
 * Wikipedia enrichment for the detail pages. The pages fetch these
 * summaries asynchronously and render fine without them.
 */
fun Route.summaryRoutes(wikipediaSummaryService: WikipediaSummaryService) {
    get("/api/summary/movie/{movieId}") {
        val movieId = call.parameters.getOrFail("movieId")
        val summary = withContext(Dispatchers.IO) { wikipediaSummaryService.findMovieSummary(movieId) }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(summary)
    }

    get("/api/summary/person/{personId}") {
        val personId = call.parameters.getOrFail("personId")
        val summary = withContext(Dispatchers.IO) { wikipediaSummaryService.findPersonSummary(personId) }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(summary)
    }
}
