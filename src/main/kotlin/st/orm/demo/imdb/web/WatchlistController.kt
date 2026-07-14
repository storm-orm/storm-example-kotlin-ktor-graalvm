package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.WatchlistService

fun Route.watchlistRoutes(watchlistService: WatchlistService) {
    get("/watchlist") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        // Page numbers in the URL are 1-based; Storm pages are 0-based.
        val watchlistPage = watchlistService.findPage((page - 1).coerceAtLeast(0))
        call.respond(
            ThymeleafContent(
                "watchlist",
                mapOf(
                    "watchlistPage" to watchlistPage,
                    "currentPage" to page
                )
            )
        )
    }
}
