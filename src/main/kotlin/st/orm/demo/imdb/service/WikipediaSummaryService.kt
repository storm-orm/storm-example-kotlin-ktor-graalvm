package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** A short description of a movie or person, fetched from Wikipedia. */
@Serializable
data class ExternalSummary(
    val title: String,
    val description: String?,
    val extract: String,
    val url: String?
)

/**
 * Enriches the dataset with Wikipedia summaries: plot descriptions for
 * movies and short biographies for persons. The IMDB dataset itself carries
 * neither, so — like the poster endpoint — this is an external enrichment
 * concern, cached for the lifetime of the application and entirely optional:
 * pages render fine without it.
 */
class WikipediaSummaryService(
    private val movieRepository: MovieRepository,
    private val personRepository: PersonRepository,
    private val objectMapper: ObjectMapper
) {

    private val summariesByKey = ConcurrentHashMap<String, Optional<ExternalSummary>>()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun findMovieSummary(movieId: String): ExternalSummary? =
        summariesByKey.computeIfAbsent("movie:$movieId") {
            val movie = movieRepository.findById(movieId)
                ?: return@computeIfAbsent Optional.empty()
            // Disambiguate common titles: prefer the year-qualified article.
            val candidateTitles = listOfNotNull(
                movie.startYear?.let { year -> "${movie.primaryTitle} ($year film)" },
                "${movie.primaryTitle} (film)",
                movie.primaryTitle
            )
            Optional.ofNullable(findFirstSummary(candidateTitles))
        }.orElse(null)

    fun findPersonSummary(personId: String): ExternalSummary? =
        summariesByKey.computeIfAbsent("person:$personId") {
            val person = personRepository.findById(personId)
                ?: return@computeIfAbsent Optional.empty()
            val candidateTitles = listOf(person.primaryName, "${person.primaryName} (actor)")
            Optional.ofNullable(findFirstSummary(candidateTitles))
        }.orElse(null)

    private fun findFirstSummary(titles: List<String>): ExternalSummary? =
        titles.firstNotNullOfOrNull { title -> fetchSummary(title) }

    private fun fetchSummary(title: String): ExternalSummary? = try {
        val encodedTitle = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)
        val uri = URI.create("https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTitle?redirect=true")
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            // Wikimedia's API policy requires a descriptive User-Agent.
            .header("User-Agent", "storm-imdb-demo/1.0 (https://orm.st)")
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) null else {
            val json = objectMapper.readTree(response.body())
            // Only standard articles qualify — skip disambiguation pages.
            if (json.path("type").asString("") != "standard") null else {
                val extract = json.path("extract").asString("")
                if (extract.isBlank()) null else ExternalSummary(
                    title = json.path("title").asString(title),
                    description = json.path("description").asString(null),
                    extract = extract,
                    url = json.path("content_urls").path("desktop").path("page").asString(null)
                )
            }
        }
    } catch (ignored: Exception) {
        null
    }
}
