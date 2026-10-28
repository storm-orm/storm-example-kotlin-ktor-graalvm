package st.orm.demo.imdb.web

import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.util.getOrFail
import java.time.LocalDate
import st.orm.demo.imdb.model.DailyGame
import st.orm.demo.imdb.service.DailyPuzzleService
import st.orm.demo.imdb.service.LevelService

/**
 * The daily puzzles besides the quiz: the pages, and the answers, which
 * post and come back to them. Anyone plays, at the level the daily asks:
 * below it, the Play page says what it takes. The account cookie keeps the game.
 */
fun Route.dailyPuzzleRoutes(dailyPuzzleService: DailyPuzzleService, levelService: LevelService) {
    /** Sends a visitor below the daily's level back to the Play page, which says what it takes; true when it did. */
    suspend fun ApplicationCall.locked(daily: DailyGame): Boolean {
        if (levelService.canPlay(levelProgress(), daily)) return false
        respondRedirect("/play?locked=${daily.name}#games")
        return true
    }

    get("/play/odd-one-out") {
        if (call.locked(DailyGame.ODD_ONE_OUT)) return@get
        call.respondPage(ThymeleafContent("odd-one-out", mapOf("odd" to dailyPuzzleService.oddOf(call.currentUser()))))
    }

    post("/play/odd-one-out") {
        if (call.locked(DailyGame.ODD_ONE_OUT)) return@post
        call.receiveParameters()["movie"]?.let { dailyPuzzleService.answerOdd(call.currentUser(), it) }
        call.respondRedirect("/play/odd-one-out")
    }

    get("/play/connections") {
        if (call.locked(DailyGame.CONNECTIONS)) return@get
        call.respondPage(ThymeleafContent("connections", mapOf(
            "grid" to dailyPuzzleService.connectionsOf(call.currentUser()),
            "guess" to call.request.queryParameters["guess"]
        )))
    }

    // A guess of four: back to the grid, saying how close it was.
    post("/play/connections") {
        if (call.locked(DailyGame.CONNECTIONS)) return@post
        val movies = call.receiveParameters().getAll("movie").orEmpty().toSet()
        val guess = dailyPuzzleService.guessConnections(call.currentUser(), movies)
        call.respondRedirect("/play/connections?guess=" + guess.name.lowercase().replace('_', '-'))
    }

    get("/play/face-off") {
        if (call.locked(DailyGame.FACE_OFF)) return@get
        call.respondPage(ThymeleafContent("face-off", mapOf("duel" to dailyPuzzleService.duelsOf(call.currentUser()))))
    }

    post("/play/face-off") {
        if (call.locked(DailyGame.FACE_OFF)) return@post
        call.receiveParameters()["movie"]?.let { dailyPuzzleService.answerDuel(call.currentUser(), it) }
        call.respondRedirect("/play/face-off")
    }

    get("/play/usual-suspects") {
        if (call.locked(DailyGame.USUAL_SUSPECTS)) return@get
        call.respondPage(ThymeleafContent("usual-suspects", mapOf("lineUp" to dailyPuzzleService.lineUpsOf(call.currentUser()))))
    }

    post("/play/usual-suspects") {
        if (call.locked(DailyGame.USUAL_SUSPECTS)) return@post
        call.receiveParameters()["person"]?.let { dailyPuzzleService.answerLineUp(call.currentUser(), it) }
        call.respondRedirect("/play/usual-suspects")
    }

    get("/play/memento") {
        if (call.locked(DailyGame.MEMENTO)) return@get
        call.respondPage(ThymeleafContent("memento", mapOf("memento" to dailyPuzzleService.seriesOf(call.currentUser()))))
    }

    post("/play/memento") {
        if (call.locked(DailyGame.MEMENTO)) return@post
        call.receiveParameters()["movie"]?.let { dailyPuzzleService.placeInSeries(call.currentUser(), it) }
        call.respondRedirect("/play/memento")
    }

    get("/play/the-mask") {
        if (call.locked(DailyGame.THE_MASK)) return@get
        call.respondPage(ThymeleafContent("the-mask", mapOf("mask" to dailyPuzzleService.masksOf(call.currentUser()))))
    }

    post("/play/the-mask") {
        if (call.locked(DailyGame.THE_MASK)) return@post
        call.receiveParameters()["person"]?.let { dailyPuzzleService.answerMask(call.currentUser(), it) }
        call.respondRedirect("/play/the-mask")
    }

    // A result shared by its link: the score and the marks, no answers; 404 for a game nobody played.
    get("/play/shared/{slug}/{user}/{day}") {
        val day = runCatching { LocalDate.parse(call.parameters.getOrFail("day")) }.getOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
        val shared = dailyPuzzleService.sharedOf(call.parameters.getOrFail("slug"), call.parameters.getOrFail("user"), day)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondPage(ThymeleafContent("shared-daily", mapOf("shared" to shared)))
    }

    get("/play/amadeus") {
        if (call.locked(DailyGame.AMADEUS)) return@get
        call.respondPage(ThymeleafContent("amadeus", mapOf("amadeus" to dailyPuzzleService.scoresOf(call.currentUser()))))
    }

    post("/play/amadeus") {
        if (call.locked(DailyGame.AMADEUS)) return@post
        call.receiveParameters()["person"]?.let { dailyPuzzleService.answerScore(call.currentUser(), it) }
        call.respondRedirect("/play/amadeus")
    }
}
