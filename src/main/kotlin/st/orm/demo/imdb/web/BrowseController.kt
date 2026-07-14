package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.BrowseService

fun Route.browseRoutes(browseService: BrowseService) {
    get("/browse/{genreName}") {
        val genreName = call.parameters.getOrFail("genreName")
        val view = browseService.browseGenre(genreName)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown genre: $genreName")
        call.respond(
            ThymeleafContent(
                "browse",
                mapOf(
                    "genre" to view.genre,
                    "movieCount" to view.movieCount,
                    "movieWindow" to view.movieWindow
                )
            )
        )
    }

    get("/api/browse/{genreName}") {
        val genreName = call.parameters.getOrFail("genreName")
        val cursor = call.request.queryParameters["cursor"]
        val window = browseService.scrollGenre(genreName, cursor)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(SearchWindow(window.content().map { it.toSearchItem() }, window.nextCursor()))
    }
}
