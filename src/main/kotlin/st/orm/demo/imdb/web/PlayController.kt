package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
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
import st.orm.demo.imdb.service.ConnectMove
import st.orm.demo.imdb.service.ConnectService
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
fun Route.playRoutes(playService: PlayService, higherLowerService: HigherLowerService, chainService: ChainService, draftService: DraftService, swipeService: SwipeService, movieNightService: MovieNightService, connectService: ConnectService) {
    get("/play") {
        val full = call.request.queryParameters["full"] != null
        call.respondPage(ThymeleafContent("play", mapOf("play" to playService.playOf(call.currentUser()), "full" to full, "maxOpen" to PlayService.MAX_OPEN)))
    }

    // The visitor's finished games, a page at a time.
    get("/play/archive") {
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        call.respondPage(ThymeleafContent("play-archive", mapOf("archive" to playService.archiveOf(call.currentUser(), page))))
    }

    // An open game closed from the list: it ends, or, in a club, the visitor leaves it.
    post("/play/matches/{id}/close") {
        playService.close(call.currentUser(), call.parameters.getOrFail<Long>("id"))
        call.respondRedirect("/play#your-games")
    }

    for ((path, start) in listOf<Pair<String, suspend (User, Opponents) -> GameMatch?>>(
        "/play/higher-lower" to { user, opponents -> higherLowerService.start(user, opponents) },
        "/play/chain" to { user, opponents -> chainService.start(user, opponents) },
        "/play/draft" to { user, opponents -> draftService.start(user, opponents) },
        "/play/swipe" to { user, opponents -> swipeService.startRound(user, opponents) },
        "/play/connect" to { user, opponents -> connectService.start(user, opponents) }
    )) {
        post(path) {
            val opponents = call.receiveParameters().opponents()
            val user = call.currentUser()
            if (opponents.mode != GameMode.SOLO && user.name == null) return@post call.respondRedirect("/signin?rd=/play")
            // At the most games open, the oldest on one's own closes; with all of them played with others, none starts.
            if (!playService.makeRoom(user)) return@post call.respondRedirect("/play?full#your-games")
            val match = start(user, opponents) ?: return@post call.respondRedirect("/play")
            call.respondRedirect("/play/matches/${match.id}")
        }
    }

    get("/play/matches/{id}") {
        val user = call.currentUser()
        val matchId = call.parameters.getOrFail<Long>("id")
        val problem = call.request.queryParameters["problem"]
        val (template, model) = higherLowerService.matchOf(user, matchId)?.let { "higher-lower" to mapOf("game" to it) }
            ?: swipeService.roundOf(user, matchId)?.let { "swipe-round" to mapOf("round" to it) }
            ?: draftService.draftOf(user, matchId)?.let { "draft" to mapOf("draft" to it, "problem" to problem) }
            ?: chainService.chainOf(user, matchId)?.let { "chain" to mapOf("chain" to it, "problem" to problem) }
            ?: connectService.puzzleOf(user, matchId)?.let { "connect" to mapOf("puzzle" to it, "problem" to problem) }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        // The match as it is now, read after the game, which may have made a pick that was due: a page that
        // waits on the others asks whether this changed.
        val version = playService.versionOf(user, matchId) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondPage(ThymeleafContent(template, model + ("version" to version)))
    }

    // How the match stands, as a short text, for a page that waits on the others: it reloads when this changes.
    get("/api/play/matches/{id}/version") {
        val version = playService.versionOf(call.currentUser(), call.parameters.getOrFail<Long>("id"))
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondText(version)
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

    // Connect, played together: a link added by any player, a link taken back, an actor shown, or giving up.
    post("/play/matches/{id}/connect/link") {
        val matchId = call.parameters.getOrFail<Long>("id")
        val movieId = call.receiveParameters()["movie"]?.takeIf { it.isNotBlank() } ?: return@post call.respondRedirect("/play/matches/$matchId")
        val problem = when (connectService.move(call.currentUser(), matchId, movieId)) {
            ConnectMove.ADDED, ConnectMove.CONNECTED, ConnectMove.OVER -> null
            ConnectMove.ALREADY_IN -> "repeat"
            ConnectMove.NO_SHARED_ACTOR -> "unlinked"
        }
        call.respondRedirect("/play/matches/$matchId" + (problem?.let { "?problem=$it" } ?: ""))
    }
    for ((action, act) in listOf<Pair<String, suspend (User, Long) -> Unit>>(
        "undo" to { user, matchId -> connectService.undo(user, matchId) },
        "hint" to { user, matchId -> connectService.hint(user, matchId) },
        "give-up" to { user, matchId -> connectService.giveUp(user, matchId) }
    )) {
        post("/play/matches/{id}/connect/$action") {
            val matchId = call.parameters.getOrFail<Long>("id")
            act(call.currentUser(), matchId)
            call.respondRedirect("/play/matches/$matchId")
        }
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
