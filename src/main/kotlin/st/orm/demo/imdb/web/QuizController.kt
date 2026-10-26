package st.orm.demo.imdb.web

import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.service.QuizService

/**
 * The daily quiz: the page, and the guesses and skips, which post and come
 * back to it. Anyone plays: the account cookie keeps the game.
 */
fun Route.quizRoutes(quizService: QuizService) {
    get("/play/quiz") {
        call.respondPage(ThymeleafContent("quiz", mapOf("quiz" to quizService.quizOf(call.currentUser()))))
    }

    post("/play/quiz/guess") {
        val movieId = call.receiveParameters()["movie"]?.takeIf { it.isNotBlank() }
        if (movieId != null) quizService.guess(call.currentUser(), movieId)
        call.respondRedirect("/play/quiz")
    }

    post("/play/quiz/skip") {
        quizService.guess(call.currentUser(), null)
        call.respondRedirect("/play/quiz")
    }
}
