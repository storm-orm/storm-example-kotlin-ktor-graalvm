package st.orm.demo.imdb.web

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.InsightsService

fun Route.insightsRoutes(insightsService: InsightsService) {
    get("/insights") {
        call.respondPage(ThymeleafContent("insights", mapOf("insights" to insightsService.insightsFor(call.currentUser()))))
    }
}
