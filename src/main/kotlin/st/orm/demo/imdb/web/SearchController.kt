package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.SearchService

fun Route.searchRoutes(searchService: SearchService) {
    get("/search") {
        val searchQuery = call.request.queryParameters["query"]?.trim().orEmpty()
        val model = buildMap<String, Any> {
            put("query", searchQuery)
            if (searchQuery.isNotEmpty()) {
                val results = searchService.search(searchQuery)
                put("movieWindow", results.movieWindow)
                put("personWindow", results.personWindow)
            }
        }
        call.respond(ThymeleafContent("search", model))
    }

    get("/api/search/suggestions") {
        val query = call.request.queryParameters.getOrFail("query")
        val suggestions = searchService.findSuggestions(query)
        call.respond(
            SearchSuggestions(
                movies = suggestions.movies.map { it.toSearchItem() },
                persons = suggestions.persons.map { it.toSearchItem() }
            )
        )
    }

    get("/api/search/movies") {
        val query = call.request.queryParameters.getOrFail("query")
        val cursor = call.request.queryParameters["cursor"]
        val window = searchService.scrollMovies(query, cursor)
        call.respond(SearchWindow(window.content().map { it.toSearchItem() }, window.nextCursor()))
    }

    get("/api/search/persons") {
        val query = call.request.queryParameters.getOrFail("query")
        val cursor = call.request.queryParameters["cursor"]
        val window = searchService.scrollPersons(query, cursor)
        call.respond(SearchWindow(window.content().map { it.toSearchItem() }, window.nextCursor()))
    }
}
