package st.orm.demo.imdb.web

import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.DailyPuzzleService

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

    get("/play/face-off") {
        call.respondPage(ThymeleafContent("face-off", mapOf("duel" to dailyPuzzleService.duelsOf(call.currentUser()))))
    }

    post("/play/face-off") {
        call.receiveParameters()["movie"]?.let { dailyPuzzleService.answerDuel(call.currentUser(), it) }
        call.respondRedirect("/play/face-off")
    }

    get("/play/usual-suspects") {
        call.respondPage(ThymeleafContent("usual-suspects", mapOf("lineUp" to dailyPuzzleService.lineUpsOf(call.currentUser()))))
    }

    post("/play/usual-suspects") {
        call.receiveParameters()["person"]?.let { dailyPuzzleService.answerLineUp(call.currentUser(), it) }
        call.respondRedirect("/play/usual-suspects")
    }

    get("/play/memento") {
        call.respondPage(ThymeleafContent("memento", mapOf("memento" to dailyPuzzleService.seriesOf(call.currentUser()))))
    }

    post("/play/memento") {
        call.receiveParameters()["movie"]?.let { dailyPuzzleService.placeInSeries(call.currentUser(), it) }
        call.respondRedirect("/play/memento")
    }

    get("/play/amadeus") {
        call.respondPage(ThymeleafContent("amadeus", mapOf("amadeus" to dailyPuzzleService.scoresOf(call.currentUser()))))
    }

    post("/play/amadeus") {
        call.receiveParameters()["person"]?.let { dailyPuzzleService.answerScore(call.currentUser(), it) }
        call.respondRedirect("/play/amadeus")
    }
}
