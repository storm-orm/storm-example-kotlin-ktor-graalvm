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
import st.orm.demo.imdb.service.DraftMove
import st.orm.demo.imdb.service.DraftService
import st.orm.demo.imdb.service.MovieNightService
import st.orm.demo.imdb.service.SwipeService
import st.orm.demo.imdb.model.SwipeWay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
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
fun Route.playRoutes(playService: PlayService, higherLowerService: HigherLowerService, chainService: ChainService, draftService: DraftService, swipeService: SwipeService, movieNightService: MovieNightService) {
    get("/play") {
        call.respondPage(ThymeleafContent("play", mapOf("play" to playService.playOf(call.currentUser()))))
    }

    for ((path, start) in listOf<Pair<String, suspend (User, Opponents) -> GameMatch?>>(
        "/play/higher-lower" to { user, opponents -> higherLowerService.start(user, opponents) },
        "/play/chain" to { user, opponents -> chainService.start(user, opponents) },
        "/play/draft" to { user, opponents -> draftService.start(user, opponents) },
        "/play/swipe" to { user, opponents -> swipeService.startRound(user, opponents) }
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
        swipeService.roundOf(user, matchId)?.let {
            return@get call.respondPage(ThymeleafContent("swipe-round", mapOf("round" to it)))
        }
        draftService.draftOf(user, matchId)?.let {
            return@get call.respondPage(ThymeleafContent("draft", mapOf("draft" to it, "problem" to call.request.queryParameters["problem"])))
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

    // A pick for the draft: back to the draft, saying what was wrong with it, if anything.
    post("/play/matches/{id}/pick") {
        val matchId = call.parameters.getOrFail<Long>("id")
        val movieId = call.receiveParameters()["movie"]?.takeIf { it.isNotBlank() } ?: return@post call.respondRedirect("/play/matches/$matchId")
        val problem = when (draftService.pick(call.currentUser(), matchId, movieId)) {
            DraftMove.PICKED, DraftMove.OVER -> null
            DraftMove.NOT_YOUR_TURN -> "turn"
            DraftMove.TAKEN -> "taken"
            DraftMove.OFF_THEME -> "theme"
        }
        call.respondRedirect("/play/matches/$matchId" + (problem?.let { "?problem=$it" } ?: ""))
    }

    // A swipe in a round: the deck posts each swipe and moves on to the next poster itself.
    post("/play/matches/{id}/swipe") {
        val matchId = call.parameters.getOrFail<Long>("id")
        val form = call.receiveParameters()
        val way = form["way"]?.let { runCatching { SwipeWay.valueOf(it) }.getOrNull() } ?: return@post call.respond(HttpStatusCode.BadRequest)
        swipeService.swipeInRound(call.currentUser(), matchId, form.getOrFail("movie"), way)
        call.respond(HttpStatusCode.NoContent)
    }

    // A club round's most wanted, straight onto the ballot of a movie night, next Friday.
    post("/play/matches/{id}/night") {
        val matchId = call.parameters.getOrFail<Long>("id")
        val user = call.currentUser()
        val round = swipeService.roundOf(user, matchId)?.takeIf { it.match.mode == GameMode.CLUB } ?: return@post call.respond(HttpStatusCode.NotFound)
        val friday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        val night = movieNightService.planWith(user, round.match.club!!.id, "Movie night", friday,
            round.results.map { it.movie to (if (it.wantedBy.size == 1) "Swiped right by 1" else "Swiped right by ${it.wantedBy.size}") })
            ?: return@post call.respondRedirect("/play/matches/$matchId")
        call.respondRedirect("/nights/${night.id}")
    }

    post("/play/matches/{id}/hint") {
        val matchId = call.parameters.getOrFail<Long>("id")
        chainService.hint(call.currentUser(), matchId)
        call.respondRedirect("/play/matches/$matchId")
    }

    post("/play/matches/{id}/give-up") {
        val matchId = call.parameters.getOrFail<Long>("id")
        chainService.giveUp(call.currentUser(), matchId)
        call.respondRedirect("/play/matches/$matchId")
    }
}
