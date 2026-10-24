package st.orm.demo.imdb.web

import io.ktor.http.content.PartData
import io.ktor.http.encodeURLParameter
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import st.orm.demo.imdb.service.CatchUpService
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.ImdbExport
import st.orm.demo.imdb.service.ImdbImport
import st.orm.demo.imdb.service.MovieSeenService

/** An export of ten thousand ratings is about two megabytes; anything far larger is not one. */
private const val MAX_IMPORT_BYTES = 8L * 1024 * 1024

fun Route.movieSeenRoutes(movieSeenService: MovieSeenService, catchUpService: CatchUpService, friendService: FriendService) {
    // The seen movies, check-ins included, latest first; with ?checkins, every check-in, by month.
    get("/seen") {
        val parameters = call.request.queryParameters
        val user = call.currentUser()
        val checkInsOnly = "checkins" in parameters
        val seen = movieSeenService.seenPage(user)
        val checkIns = if (checkInsOnly) movieSeenService.checkInsPage(user) else null
        // The outcome of an import, carried over its redirect.
        val imported = parameters["added"]?.toIntOrNull()?.let { added ->
            ImdbImport(
                added = added,
                updated = parameters["updated"]?.toIntOrNull() ?: 0,
                checkIns = parameters["checkIns"]?.toIntOrNull() ?: 0,
                skipped = parameters["skipped"]?.toIntOrNull() ?: 0
            )
        }
        call.respondPage(
            ThymeleafContent(
                "seen",
                mapOf(
                    "count" to seen.count,
                    "movies" to seen.window.content().map { it.movie },
                    "checkIns" to checkIns,
                    "nextCursor" to (checkIns?.window ?: seen.window).nextCursor(),
                    "imported" to imported,
                    "importFailed" to (parameters["import"] == "failed")
                )
            )
        )
    }
    get("/api/seen/list") {
        val parameters = call.request.queryParameters
        val user = call.currentUser()
        val cursor = parameters["cursor"].orEmpty()
        if ("checkins" in parameters) {
            val window = movieSeenService.scrollCheckIns(user, cursor)
            call.respond(SearchWindow(window.content().map { it.toCheckInItem() }, window.nextCursor()))
        } else {
            val window = movieSeenService.scrollSeen(user, cursor)
            call.respond(SearchWindow(window.content().map { it.movie.toSearchItem() }, window.nextCursor()))
        }
    }

    // Catching up: the most voted movies, of every genre or of one, to mark the ones seen long ago;
    // with ?unmarked, only the ones the visitor has not marked yet.
    get("/seen/catch-up") {
        val parameters = call.request.queryParameters
        val view = catchUpService.catchUp(parameters["genre"], call.currentUser().takeIf { "unmarked" in parameters })
        // The scroll asks for the next windows with the same filters.
        val filters = listOfNotNull(view.genre?.let { "genre=" + it.name.encodeURLParameter() }, "unmarked".takeIf { view.unmarkedOnly })
        call.respondPage(ThymeleafContent("catch-up", mapOf(
            "genres" to view.genres,
            "genre" to view.genre,
            "unmarkedOnly" to view.unmarkedOnly,
            "movies" to view.window.content().map { it.movie },
            "nextCursor" to view.window.nextCursor(),
            "scrollEndpoint" to "/api/seen/catch-up" + filters.joinToString("&", prefix = "?").takeIf { filters.isNotEmpty() }.orEmpty()
        )))
    }
    get("/api/seen/catch-up") {
        val parameters = call.request.queryParameters
        val window = catchUpService.scroll(parameters["genre"], call.currentUser().takeIf { "unmarked" in parameters }, parameters["cursor"].orEmpty())
        call.respond(SearchWindow(window.content().map { it.movie.toSearchItem() }, window.nextCursor()))
    }

    // The seen marks, by movie id, for the tags the page puts on its lists of movies: the visitor's own, and their friends'.
    get("/api/seen") {
        val user = call.currentUser()
        call.respond(SeenMarks(
            seen = movieSeenService.seenDates(user).mapValues { it.value?.toString() },
            friends = friendService.seenByFriends(user)
        ))
    }

    // The IMDb export arrives as a file upload. The file is read before the
    // import's transaction opens, and the page answers with a redirect to the
    // seen list, so reloading it does not upload the file again.
    post("/seen/import") {
        var text: String? = null
        call.receiveMultipart(formFieldLimit = MAX_IMPORT_BYTES).forEachPart { part ->
            if (part is PartData.FileItem && text == null) text = part.provider().readRemaining().readString()
            part.dispose()
        }
        val rows = try {
            ImdbExport.parse(text.orEmpty())
        } catch (e: IllegalArgumentException) {
            return@post call.respondRedirect("/seen?import=failed")
        }
        val result = movieSeenService.importFromImdb(call.currentUser(), rows)
        call.respondRedirect("/seen?added=${result.added}&updated=${result.updated}&checkIns=${result.checkIns}&skipped=${result.skipped}")
    }
}
