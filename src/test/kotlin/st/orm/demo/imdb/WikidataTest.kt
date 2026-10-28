package st.orm.demo.imdb

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.Tag
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieTagRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.TopMoviesSort
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WikidataSourceRepository
import st.orm.demo.imdb.service.WikidataClient
import st.orm.demo.imdb.service.TmdbClient
import st.orm.demo.imdb.service.WikidataService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wikidata as a third source: where the movies were made and in what
 * language, which become their languages and tags, and the filter that keeps
 * the lists to the tags and languages a user wants. The query service is a
 * local server answering as Wikidata does: The Matrix and The Godfather made
 * in the United States, The Shawshank Redemption, for the test, in India in
 * a language it does not name, Amélie in France in French; the other two it
 * has no item for. The same server answers as TMDB, which has the language
 * of The Shawshank Redemption, Hindi, and of Pulp Fiction, English.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class WikidataTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val tmdbRequests = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun startWikidata() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/3/find/") { exchange ->
            tmdbRequests += exchange.requestURI.path
            val language = TMDB_LANGUAGES[exchange.requestURI.path.substringAfterLast('/')]
            val body = if (language != null) """{"movie_results":[{"id":1,"original_language":"$language"}]}""" else """{"movie_results":[]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/sparql") { exchange ->
            val query = exchange.requestBody.readAllBytes().decodeToString()
            requests += query
            val bindings = ANSWERS.filterKeys { it in query }.values.joinToString(",")
            val bytes = """{"results":{"bindings":[$bindings]}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopWikidata() = server.stop(0)

    private fun wikidataService(orm: ORMTemplate) = WikidataService(
        WikidataClient(jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}/sparql"),
        TmdbClient("test-key", jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}"),
        orm.repository<MovieRepository>(),
        orm.repository<WikidataSourceRepository>(),
        orm.repository<MovieLanguageRepository>(),
        orm.repository<MovieCountryRepository>(),
        orm.repository<MovieTagRepository>()
    )

    @Test
    fun `each tag has its rule`() {
        assertEquals(listOf(Tag.HOLLYWOOD), WikidataService.tagsOf(setOf("US", "GB"), setOf("en")))
        assertEquals(listOf(Tag.BOLLYWOOD), WikidataService.tagsOf(setOf("IN"), setOf("hi")))
        // Made in India in Telugu is Indian cinema, not Bollywood; made in France, neither.
        assertEquals(emptyList<Tag>(), WikidataService.tagsOf(setOf("IN"), setOf("te")))
        assertEquals(emptyList<Tag>(), WikidataService.tagsOf(setOf("FR"), setOf("fr")))
    }

    @Test
    fun `every movie is asked once, and gets its languages and tags`(orm: ORMTemplate) {
        val now = Instant.parse("2026-10-25T04:00:00Z")
        runBlocking { wikidataService(orm).askAboutMovies(now) }

        // The catalogue's six movies fit one window: one query. TMDB is asked about the three without
        // a language on Wikidata: The Shawshank Redemption, and the two Wikidata has no item for.
        assertEquals(1, requests.size)
        assertEquals(setOf("/3/find/tt0111161", "/3/find/tt0110912", "/3/find/tt0234215"), tmdbRequests.toSet())
        val sources = orm.repository<WikidataSourceRepository>().findAll().associate { it.movie.id() to it.externalId }
        assertEquals(6, sources.size)
        assertEquals("Q83495", sources["tt0133093"])
        assertEquals(null, sources["tt0110912"])  // Pulp Fiction: asked, and no item
        val tags = orm.repository<MovieTagRepository>().findAll().map { it.id.movieId to it.id.tag }.toSet()
        assertEquals(setOf("tt0133093" to Tag.HOLLYWOOD, "tt0068646" to Tag.HOLLYWOOD, "tt0111161" to Tag.BOLLYWOOD), tags)
        val languages = orm.repository<MovieLanguageRepository>()
        val movies = orm.repository<MovieRepository>()
        assertEquals(listOf("en", "it"), languages.findLanguages(movies.getById("tt0068646")).sorted())
        // TMDB's languages fill Wikidata's gaps: Hindi makes The Shawshank Redemption, made in India, Bollywood.
        assertEquals(listOf("hi"), languages.findLanguages(movies.getById("tt0111161")))
        assertEquals(listOf("en"), languages.findLanguages(movies.getById("tt0110912")))
        assertEquals(emptyList<String>(), languages.findLanguages(movies.getById("tt0234215")))
        // The countries too, for the recommendations.
        assertEquals(listOf("IN"), orm.repository<MovieCountryRepository>().findByMovies(listOf(movies.getById("tt0111161").ref())).map { it.id.country })

        // Asked lately, a movie is not asked again until it is due.
        runBlocking { wikidataService(orm).askAboutMovies(now.plusSeconds(3600)) }
        assertEquals(1, requests.size)
    }

    @Test
    fun `a filter keeps the lists to the tags and languages a user wants, by semi-joins and anti-joins`(orm: ORMTemplate, capture: SqlCapture) {
        runBlocking { wikidataService(orm).askAboutMovies(Instant.parse("2026-10-25T04:00:00Z")) }
        val ratings = orm.repository<RatingRepository>()
        fun topMovies(filter: MovieFilter?) =
            ratings.findTopMovies(null, TopMoviesSort.RATING, filter, minimumVoteCount = 1000, limit = 10).map { it.movie.primaryTitle }.toSet()

        assertEquals(6, topMovies(null).size)
        // No Hollywood: the anti-join leaves out The Matrix and The Godfather.
        val noHollywood = capture.execute { topMovies(MovieFilter(hiddenTags = setOf(Tag.HOLLYWOOD))) }
        capture.printStatements("filter: no Hollywood")
        assertEquals(setOf("The Shawshank Redemption", "Pulp Fiction", "The Matrix Reloaded", "Amélie"), noHollywood)
        assertTrue(capture.statements().single().statement().contains("NOT EXISTS"))
        // Nothing else: the semi-join keeps the tagged movies.
        assertEquals(setOf("The Matrix", "The Godfather", "The Shawshank Redemption"), topMovies(MovieFilter(showsUntagged = false)))
        // Bollywood only: an anti-join and a semi-join on the same table, each a subquery of its own.
        val bollywood = capture.execute { topMovies(MovieFilter(hiddenTags = setOf(Tag.HOLLYWOOD), showsUntagged = false)) }
        capture.printStatements("filter: Bollywood only")
        assertEquals(setOf("The Shawshank Redemption"), bollywood)
        // In French: the semi-join on the languages.
        assertEquals(setOf("Amélie"), topMovies(MovieFilter(languages = setOf("fr"))))
        // Both: Hollywood in Italian, of which The Godfather.
        assertEquals(setOf("The Godfather"), topMovies(MovieFilter(showsUntagged = false, languages = setOf("it"))))
    }

    @Test
    fun `a user's filter is stored with them as JSON`(orm: ORMTemplate) {
        val users = orm.repository<UserRepository>()
        val user = orm.insertUser("alice", name = "Alice")
        val filter = MovieFilter(hiddenTags = setOf(Tag.BOLLYWOOD), showsUntagged = false, languages = setOf("en", "fr"))
        users.update(user.copy(movieFilter = filter))
        assertEquals(filter, users.getById("alice").movieFilter)
    }

    private companion object {
        fun answer(imdbId: String, item: String, countries: String, languages: String) = """
            {"imdb":{"type":"literal","value":"$imdbId"},
             "item":{"type":"uri","value":"http://www.wikidata.org/entity/$item"},
             "countries":{"type":"literal","value":"$countries"},
             "languages":{"type":"literal","value":"$languages"}}
        """.trimIndent()

        val TMDB_LANGUAGES = mapOf("tt0111161" to "hi", "tt0110912" to "en")

        val ANSWERS = mapOf(
            "tt0133093" to answer("tt0133093", "Q83495", "US", "en"),
            "tt0068646" to answer("tt0068646", "Q47703", "US", "en it"),
            "tt0111161" to answer("tt0111161", "Q172241", "IN", ""),
            "tt0211915" to answer("tt0211915", "Q484048", "FR", "fr")
        )
    }
}
