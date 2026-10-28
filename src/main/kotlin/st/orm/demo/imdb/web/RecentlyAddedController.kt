package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.RecentlyAddedService

fun Route.recentlyAddedRoutes(recentlyAddedService: RecentlyAddedService) {
    get("/recent") {
        val view = recentlyAddedService.recentlyAdded(call.currentUser().movieFilter)
        call.respondPage(
            ThymeleafContent(
                "recent",
                mapOf(
                    "days" to view.days,
                    "nextCursor" to view.nextCursor
                )
            )
        )
    }

    get("/api/recent") {
        val cursor = call.request.queryParameters["cursor"]
        val window = recentlyAddedService.scroll(call.currentUser().movieFilter, cursor)
        call.respond(SearchWindow(window.content().map { it.toAddedItem() }, window.nextCursor()))
    }
}
