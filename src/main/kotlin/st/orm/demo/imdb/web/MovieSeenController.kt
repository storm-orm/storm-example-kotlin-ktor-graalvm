package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.MovieSeenService

fun Route.movieSeenRoutes(movieSeenService: MovieSeenService) {
    get("/seen") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        // Page numbers in the URL are 1-based; Storm pages are 0-based.
        val seenPage = movieSeenService.findPage(call.currentUser(), (page - 1).coerceAtLeast(0))
        call.respondPage(
            ThymeleafContent(
                "seen",
                mapOf(
                    "seenPage" to seenPage,
                    "currentPage" to page
                )
            )
        )
    }
}
