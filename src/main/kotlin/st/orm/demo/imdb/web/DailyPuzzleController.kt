package st.orm.demo.imdb.web

import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.DailyPuzzleService
import st.orm.demo.imdb.service.GridGuess

/**
 * The daily puzzles besides the quiz: the pages, and the answers, which
 * post and come back to them. Anyone plays: the account cookie keeps the game.
 */
fun Route.dailyPuzzleRoutes(dailyPuzzleService: DailyPuzzleService) {
    get("/play/odd-one-out") {
        call.respondPage(ThymeleafContent("odd-one-out", mapOf("odd" to dailyPuzzleService.oddOf(call.currentUser()))))
    }

    post("/play/odd-one-out") {
        call.receiveParameters()["movie"]?.let { dailyPuzzleService.answerOdd(call.currentUser(), it) }
        call.respondRedirect("/play/odd-one-out")
    }

    get("/play/connections") {
        call.respondPage(ThymeleafContent("connections", mapOf(
            "grid" to dailyPuzzleService.connectionsOf(call.currentUser()),
            "guess" to call.request.queryParameters["guess"]
        )))
    }

    // A guess of four: back to the grid, saying how close it was.
    post("/play/connections") {
        val movies = call.receiveParameters().getAll("movie").orEmpty().toSet()
        val guess = dailyPuzzleService.guessConnections(call.currentUser(), movies)
        call.respondRedirect("/play/connections?guess=" + guess.name.lowercase().replace('_', '-'))
    }
}
