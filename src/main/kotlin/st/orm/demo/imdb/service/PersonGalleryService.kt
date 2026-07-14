package st.orm.demo.imdb.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.PersonGallery
import st.orm.demo.imdb.model.Photo
import st.orm.demo.imdb.repository.PersonGalleryRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.template.transaction
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Galleries are only useful in small doses; keep the first dozen photos. */
private const val MAX_GALLERY_PHOTOS = 12

private val logger = LoggerFactory.getLogger(PersonGalleryService::class.java)

/**
 * Photo galleries for the person pages, sourced from the images of the
 * person's Wikipedia article. Unlike the poster and summary enrichment,
 * galleries are not held in memory: the first request fetches the photos
 * from Wikimedia and writes them to the person_gallery table, and every
 * request after that — across restarts and across instances — reads the
 * stored gallery from the database.
 */
class PersonGalleryService(
    private val personRepository: PersonRepository,
    private val personGalleryRepository: PersonGalleryRepository,
    private val wikipediaSummaryService: WikipediaSummaryService,
    private val objectMapper: ObjectMapper
) {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * The gallery for a person: the stored photos when present, otherwise
     * freshly fetched from Wikimedia and stored — including an empty result,
     * so an article without usable photos is remembered as such. Transient
     * fetch failures are not stored; a later request simply tries again.
     * Returns null for unknown persons.
     */
    suspend fun findGallery(personId: String): List<Photo>? {
        val (person, stored) = transaction(readOnly = true) {
            personRepository.findById(personId)?.let { it to personGalleryRepository.findById(it) }
        } ?: return null
        stored?.let { return it.photos }
        // Fetch outside the transaction: no connection is held during HTTP I/O.
        val photos = fetchPhotos(person) ?: return emptyList()
        transaction {
            personGalleryRepository.upsert(PersonGallery(person, photos, Instant.now()))
        }
        return photos
    }

    // Main-safe: the blocking HTTP clients run on the IO dispatcher.
    private suspend fun fetchPhotos(person: Person): List<Photo>? = withContext(Dispatchers.IO) {
        // The summary lookup resolves the person to a canonical article
        // title — it already skips disambiguation pages.
        val article = wikipediaSummaryService.findPersonSummary(person.id) ?: return@withContext null
        try {
            val encodedTitle = URLEncoder.encode(article.title.replace(' ', '_'), StandardCharsets.UTF_8)
            val uri = URI.create("https://en.wikipedia.org/api/rest_v1/page/media-list/$encodedTitle")
            val request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                // Wikimedia's API policy requires a descriptive User-Agent.
                .header("User-Agent", "storm-imdb-demo/1.0 (https://orm.st)")
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) null else {
                objectMapper.readTree(response.body()).path("items")
                    .filter { it.path("type").asString("") == "image" && it.path("showInGallery").asBoolean(false) }
                    .mapNotNull { toPhoto(it) }
                    .take(MAX_GALLERY_PHOTOS)
            }
        } catch (e: CancellationException) {
            throw e // Never swallow cancellation.
        } catch (e: Exception) {
            // Galleries are optional enrichment: a failed fetch must never
            // break the person page, but it should leave a trace. Nothing is
            // stored, so the next request tries again.
            logger.warn("Fetching the photo gallery for {} ({}) failed", person.primaryName, person.id, e)
            null
        }
    }

    /** The srcset is ordered by scale; the last entry is the sharpest. */
    private fun toPhoto(item: JsonNode): Photo? {
        // Actor infoboxes usually carry a signature image — not a photo.
        if (item.path("title").asString("").contains("signature", ignoreCase = true)) return null
        val src = item.path("srcset").lastOrNull()?.path("src")?.asString(null) ?: return null
        // Vector graphics are flags, seals and logos — not photos.
        if (src.contains(".svg", ignoreCase = true)) return null
        return Photo(
            url = if (src.startsWith("//")) "https:$src" else src,
            caption = item.path("caption").path("text").asString(null)
        )
    }
}
