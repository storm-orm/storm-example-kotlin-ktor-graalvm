package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.StatisticsService

fun Route.statisticsRoutes(statisticsService: StatisticsService) {
    get("/statistics") {
        val view = statisticsService.buildStatisticsView()
        call.respond(
            ThymeleafContent(
                "statistics",
                mapOf(
                    "decades" to view.decades,
                    "maxDecadeCount" to view.maxDecadeCount,
                    "genreStatistics" to view.genreStatistics,
                    "prolificActors" to view.prolificActors
                )
            )
        )
    }
}
