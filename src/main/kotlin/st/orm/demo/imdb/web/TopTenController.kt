package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.SearchService
import st.orm.demo.imdb.service.TopTenService

/**
 * The visitor's top ten: the page, a movie put in by its id or a typed
 * title, one taken out, and the order the page's dragging put them in.
 */
fun Route.topTenRoutes(topTenService: TopTenService, searchService: SearchService) {
    get("/top-ten") {
        call.respondPage(ThymeleafContent("top-ten", mapOf("topTen" to topTenService.topTenOf(call.currentUser()))))
    }

    post("/top-ten/add") {
        val form = call.receiveParameters()
        // The picker gives the movie's id; a title typed and sent takes the best match.
        val movieId = form["movie"]?.takeIf { it.isNotBlank() }
            ?: form["title"]?.trim()?.takeIf { it.isNotEmpty() }?.let { searchService.findTitleSuggestions(it).firstOrNull()?.id }
        movieId?.let { topTenService.add(call.currentUser(), it) }
        call.respondRedirect("/top-ten")
    }

    post("/top-ten/remove") {
        call.receiveParameters()["movie"]?.let { topTenService.remove(call.currentUser(), it) }
        call.respondRedirect("/top-ten")
    }

    // The order the visitor dragged the list into: the movie ids, best first.
    post("/api/top-ten/order") {
        topTenService.reorder(call.currentUser(), call.receive<List<String>>())
        call.respond(HttpStatusCode.NoContent)
    }
}
