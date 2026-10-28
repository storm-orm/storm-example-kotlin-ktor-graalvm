package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import io.ktor.http.encodeURLParameter
import st.orm.demo.imdb.service.BrowseService
import st.orm.demo.imdb.service.BrowseSort

/**
 * Browsing the catalogue: one page for every genre or one, in one of three
 * orders. The top movies and the genre pages it replaced redirect here, so
 * their links keep working.
 */
fun Route.browseRoutes(browseService: BrowseService) {
    get("/browse") {
        val parameters = call.request.queryParameters
        val genre = parameters["genre"]?.takeIf { it.isNotBlank() }
        val sort = BrowseSort.of(parameters["sort"])
        val view = browseService.browse(genre, sort, call.currentUser().movieFilter)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown genre: $genre")
        call.respondPage(ThymeleafContent("browse", mapOf("view" to view, "sort" to sort.key)))
    }

    get("/api/browse") {
        val parameters = call.request.queryParameters
        val window = browseService.scroll(parameters["genre"]?.takeIf { it.isNotBlank() }, call.currentUser().movieFilter, parameters.getOrFail("cursor"))
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(SearchWindow(window.content().map { it.movie.toSearchItem() }, window.nextCursor()))
    }

    // The links of before: the top movies, with their genre and sort, and a genre's own page.
    get("/top") {
        val parameters = call.request.queryParameters
        val query = listOfNotNull(parameters["genre"]?.let { "genre=" + it.encodeURLParameter() }, parameters["sort"]?.let { "sort=" + it.encodeURLParameter() })
        call.respondRedirect("/browse" + query.joinToString("&", prefix = "?").takeIf { query.isNotEmpty() }.orEmpty(), permanent = true)
    }
    get("/browse/{genreName}") {
        call.respondRedirect("/browse?genre=" + call.parameters.getOrFail("genreName").encodeURLParameter() + "&sort=votes", permanent = true)
    }
}
