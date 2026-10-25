package st.orm.demo.imdb.service

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * What Wikidata says of a movie: its item ("Q83495"), the countries it was
 * made in (ISO 3166-1 alpha-2, "US") and the languages it was made in (ISO
 * 639, "en").
 */
data class WikidataMovie(
    val itemId: String,
    val countries: Set<String>,
    val languages: Set<String>
)

/**
 * The one question this application asks Wikidata's query service: what it
 * says of a batch of movies, found by their IMDb ids (property P345). One
 * query answers a couple of hundred movies, and Wikidata's data is free to
 * keep, so the whole catalogue is a few hundred calls. Its policy asks for a
 * User-Agent that says who calls; a query service that cannot be reached, or
 * refuses, throws [IOException].
 */
class WikidataClient(
    private val objectMapper: ObjectMapper,
    private val endpoint: String = "https://query.wikidata.org/sparql"
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** What Wikidata says of the movies with these IMDb ids, by IMDb id; a movie it has no item for is left out. */
    fun findMovies(imdbIds: Collection<String>): Map<String, WikidataMovie> {
        val values = imdbIds.filter { IMDB_ID.matches(it) }.joinToString(" ") { "\"$it\"" }
        if (values.isEmpty()) return emptyMap()
        val form = "format=json&query=" + URLEncoder.encode(MOVIES_QUERY.replace("\$imdbIds", values), StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", "storm-imdb-demo/1.0 (https://orm.st)")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("Wikidata answered ${response.statusCode()}")
        return objectMapper.readTree(response.body()).path("results").path("bindings")
            .map(::movieOf)
            .groupBy { it.first }
            .mapValues { (_, items) -> items.minBy { it.second.itemId.drop(1).toLong() }.second }
    }

    /** One row of the answer: an IMDb id with what the item says. An IMDb id that two items name keeps the oldest. */
    private fun movieOf(row: JsonNode): Pair<String, WikidataMovie> =
        row.value("imdb") to WikidataMovie(
            itemId = row.value("item").substringAfterLast('/'),
            countries = row.values("countries"),
            languages = row.values("languages")
        )

    private fun JsonNode.value(name: String): String = path(name).path("value").asString()

    private fun JsonNode.values(name: String): Set<String> = value(name).split(' ').filter { it.isNotEmpty() }.toSet()

    private companion object {
        val IMDB_ID = Regex("tt\\d+")

        /**
         * The movies' items, the countries they were made in (P495) by their
         * ISO code (P297), and their original languages (P364) by their
         * two-letter ISO code (P218), or else the three-letter one (P220).
         */
        val MOVIES_QUERY = """
            SELECT ?imdb ?item
                (GROUP_CONCAT(DISTINCT ?country; separator = " ") AS ?countries)
                (GROUP_CONCAT(DISTINCT ?language; separator = " ") AS ?languages)
            WHERE {
                VALUES ?imdb { ${'$'}imdbIds }
                ?item wdt:P345 ?imdb .
                OPTIONAL { ?item wdt:P495/wdt:P297 ?country }
                OPTIONAL {
                    ?item wdt:P364 ?spoken .
                    OPTIONAL { ?spoken wdt:P218 ?twoLetters }
                    OPTIONAL { ?spoken wdt:P220 ?threeLetters }
                    BIND(COALESCE(?twoLetters, ?threeLetters) AS ?language)
                }
            }
            GROUP BY ?imdb ?item
        """.trimIndent()
    }
}
