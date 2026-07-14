package st.orm.demo.imdb.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.PersonGalleryService

/**
 * Photo galleries for the person detail pages. The page fetches the gallery
 * asynchronously and renders fine without it.
 */
fun Route.galleryRoutes(personGalleryService: PersonGalleryService) {
    get("/api/gallery/person/{personId}") {
        val personId = call.parameters.getOrFail("personId")
        val photos = personGalleryService.findGallery(personId)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(photos)
    }
}
