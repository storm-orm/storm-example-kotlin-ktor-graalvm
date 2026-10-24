package st.orm.demo.imdb.web

import io.ktor.http.content.PartData
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
import st.orm.demo.imdb.service.ImdbExport
import st.orm.demo.imdb.service.ImdbImport
import st.orm.demo.imdb.service.MovieSeenService

/** An export of ten thousand ratings is about two megabytes; anything far larger is not one. */
private const val MAX_IMPORT_BYTES = 8L * 1024 * 1024

fun Route.movieSeenRoutes(movieSeenService: MovieSeenService) {
    get("/seen") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        // Page numbers in the URL are 1-based; Storm pages are 0-based.
        val seenPage = movieSeenService.findPage(call.currentUser(), (page - 1).coerceAtLeast(0))
        // The outcome of an import, carried over its redirect.
        val parameters = call.request.queryParameters
        val imported = parameters["added"]?.toIntOrNull()?.let { added ->
            ImdbImport(added, parameters["updated"]?.toIntOrNull() ?: 0, parameters["skipped"]?.toIntOrNull() ?: 0)
        }
        call.respondPage(
            ThymeleafContent(
                "seen",
                mapOf(
                    "seenPage" to seenPage,
                    "currentPage" to page,
                    "imported" to imported,
                    "importFailed" to (parameters["import"] == "failed")
                )
            )
        )
    }

    // The seen dates, by movie id, for the seen tags the page puts on its lists of movies.
    get("/api/seen") {
        call.respond(movieSeenService.seenDates(call.currentUser()).mapValues { it.value.toString() })
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
        call.respondRedirect("/seen?added=${result.added}&updated=${result.updated}&skipped=${result.skipped}")
    }
}
