package st.orm.demo.imdb.web

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.thymeleaf.ThymeleafContent
import st.orm.demo.imdb.repository.TopMoviesSort
import st.orm.demo.imdb.service.TopMoviesService

fun Route.topMoviesRoutes(topMoviesService: TopMoviesService) {
    get("/top") {
        val genre = call.request.queryParameters["genre"]
        val sort = call.request.queryParameters["sort"] ?: "rating"
        val sortBy = if (sort == "year") TopMoviesSort.YEAR else TopMoviesSort.RATING
        val view = topMoviesService.findTopMovies(genre, sortBy)
        val model = buildMap<String, Any> {
            put("genres", view.genres)
            view.selectedGenre?.let { put("selectedGenre", it) }
            put("sort", if (sortBy == TopMoviesSort.YEAR) "year" else "rating")
            put("entries", view.entries)
        }
        call.respond(ThymeleafContent("top", model))
    }
}
