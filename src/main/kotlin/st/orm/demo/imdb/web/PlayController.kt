package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.service.ChainMove
import st.orm.demo.imdb.service.ChainService
import st.orm.demo.imdb.service.HigherLowerService
import st.orm.demo.imdb.service.Opponents
import st.orm.demo.imdb.service.PlayService

/** Who a new match is with, from the form that starts it: on one's own, or the friend or the club picked. */
private fun Parameters.opponents(): Opponents = when (this["mode"]) {
    "friend" -> Opponents(GameMode.FRIEND, friendId = this["friend"])
    "club" -> Opponents(GameMode.CLUB, clubId = this["club"]?.toLongOrNull())
    else -> Opponents(GameMode.SOLO)
}

/**
 * The games: the Play page with the visitor's games and the ways to start
 * one, on one's own, with a friend or with a club, and a match's page,
 * whatever its game. Every move is a form post, answered with a redirect
 * to the match. Playing with others needs a signed-in account, as they see
 * the players' names; anyone plays on their own.
 */
fun Route.playRoutes(playService: PlayService, higherLowerService: HigherLowerService, chainService: ChainService) {
    get("/play") {
        call.respondPage(ThymeleafContent("play", mapOf("play" to playService.playOf(call.currentUser()))))
    }

    for ((path, start) in listOf<Pair<String, suspend (User, Opponents) -> GameMatch?>>(
        "/play/higher-lower" to { user, opponents -> higherLowerService.start(user, opponents) },
        "/play/chain" to { user, opponents -> chainService.start(user, opponents) }
    )) {
        post(path) {
            val opponents = call.receiveParameters().opponents()
            val user = call.currentUser()
            if (opponents.mode != GameMode.SOLO && user.name == null) return@post call.respondRedirect("/signin?rd=/play")
            val match = start(user, opponents) ?: return@post call.respondRedirect("/play")
            call.respondRedirect("/play/matches/${match.id}")
        }
    }

    get("/play/matches/{id}") {
        val user = call.currentUser()
        val matchId = call.parameters.getOrFail<Long>("id")
        higherLowerService.matchOf(user, matchId)?.let {
            return@get call.respondPage(ThymeleafContent("higher-lower", mapOf("game" to it)))
        }
        chainService.chainOf(user, matchId)?.let {
            return@get call.respondPage(ThymeleafContent("chain", mapOf("chain" to it, "problem" to call.request.queryParameters["problem"])))
        }
        call.respond(HttpStatusCode.NotFound)
    }

    post("/play/matches/{id}/answer") {
        val matchId = call.parameters.getOrFail<Long>("id")
        higherLowerService.answer(call.currentUser(), matchId, higher = call.receiveParameters()["higher"] == "true")
        call.respondRedirect("/play/matches/$matchId")
    }

    // A movie for the chain: back to the chain, saying what was wrong with it, if anything.
    post("/play/matches/{id}/move") {
        val matchId = call.parameters.getOrFail<Long>("id")
        val movieId = call.receiveParameters()["movie"]?.takeIf { it.isNotBlank() } ?: return@post call.respondRedirect("/play/matches/$matchId")
        val problem = when (chainService.move(call.currentUser(), matchId, movieId)) {
            ChainMove.ADDED -> null
            ChainMove.NOT_YOUR_TURN -> "turn"
            ChainMove.ALREADY_IN -> "repeat"
            ChainMove.NO_SHARED_ACTOR -> "unlinked"
            ChainMove.OVER -> null
        }
        call.respondRedirect("/play/matches/$matchId" + (problem?.let { "?problem=$it" } ?: ""))
    }

    post("/play/matches/{id}/give-up") {
        val matchId = call.parameters.getOrFail<Long>("id")
        chainService.giveUp(call.currentUser(), matchId)
        call.respondRedirect("/play/matches/$matchId")
    }
}
