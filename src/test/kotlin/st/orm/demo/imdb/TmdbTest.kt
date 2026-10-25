package st.orm.demo.imdb

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.Source
import st.orm.demo.imdb.model.source
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieSourceRepository
import st.orm.demo.imdb.repository.TmdbSourceRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.Countries
import st.orm.demo.imdb.service.ExportRow
import st.orm.demo.imdb.service.HistoryImport
import st.orm.demo.imdb.service.RecommendedMovie
import st.orm.demo.imdb.service.StreamingService
import st.orm.demo.imdb.service.TmdbClient
import st.orm.demo.imdb.service.TmdbExport
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TMDB as a second source: the export it mails, the trailers, and the IMDb
 * ids behind its own. The API is a local server answering as TMDB does, for
 * The Matrix (TMDB 603) and for movies TMDB does not have.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class TmdbTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun startTmdb() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/3/") { exchange ->
            requests += exchange.requestURI.toString()
            val body = when (exchange.requestURI.path) {
                "/3/find/tt0133093" -> """{"movie_results":[{"id":603,"title":"The Matrix"}]}"""
                "/3/movie/603" -> MATRIX
                "/3/watch/providers/movie" -> NETHERLANDS
                else -> if (exchange.requestURI.path.startsWith("/3/find/")) """{"movie_results":[]}""" else null
            }
            val bytes = (body ?: """{"status_code":34}""").toByteArray()
            exchange.sendResponseHeaders(if (body != null) 200 else 404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopTmdb() = server.stop(0)

    private fun client(apiKey: String? = "test-key") =
        TmdbClient(apiKey, jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}")

    @Test
    fun `a TMDB export is read by its header, movies only, the ratings rounded`() {
        val csv = """
            TMDb ID,IMDb ID,Type,Name,Release Date,Your Rating,Date Rated
            603,tt0133093,movie,The Matrix,1999-03-30,9.5,2024-02-11T20:15:00Z
            1396,tt0903747,tv,Breaking Bad,2008-01-20,10,2024-03-01T10:00:00Z
            278,,movie,The Shawshank Redemption,1994-09-23,4.5,2023-07-04
        """.trimIndent()

        assertTrue(TmdbExport.matches(csv))
        // A movie the export gives an IMDb id for is named by it, the others by their TMDB id.
        assertEquals(
            listOf(
                ExportRow(Source.IMDB, "tt0133093", LocalDate.of(2024, 2, 11), 10),
                ExportRow(Source.TMDB, "278", LocalDate.of(2023, 7, 4), 5)
            ),
            TmdbExport.parse(csv)
        )
        // An IMDb export is not one, and a file without the column is refused.
        assertTrue(!TmdbExport.matches("Const,Your Rating,Date Rated\ntt0133093,9,2021-03-14\n"))
        assertThrows<IllegalArgumentException> { TmdbExport.parse("name,email\nAlice,alice@example.com\n") }
    }

    @Test
    fun `an import finds movies by any source, and asks TMDB once for the TMDB ids it does not know`(orm: ORMTemplate) {
        val user = orm.insertUser("alice", name = "Alice")
        val service = movieSeenService(orm, client())
        val rows = listOf(
            ExportRow(Source.TMDB, "603", LocalDate.of(2024, 2, 11), 9),       // The Matrix, by its TMDB id
            ExportRow(Source.IMDB, "tt0111161", LocalDate.of(2023, 7, 4), 4),  // The Shawshank Redemption, by its IMDb id
            ExportRow(Source.TMDB, "999999", null, 7)                           // a movie TMDB does not have
        )

        val result = runBlocking { service.importHistory(user, rows) }

        assertEquals(HistoryImport(added = 2, updated = 0, checkIns = 2, skipped = 1), result)
        val seen = orm.repository<MovieSeenRepository>()
        val movies = orm.repository<MovieRepository>()
        assertEquals(true, seen.getById(MovieSeenPk(user.id, "tt0133093")).liked)
        assertEquals(false, seen.getById(MovieSeenPk(user.id, "tt0111161")).liked)
        // TMDB was asked about the two TMDB ids, and The Matrix now has a TMDB source next to its IMDb one.
        assertEquals(setOf("/3/movie/603", "/3/movie/999999"), requests.map { it.substringBefore('?') }.toSet())
        assertTrue(requests.all { "api_key=test-key" in it })
        val matrix = movies.getById("tt0133093")
        val stored = orm.repository<TmdbSourceRepository>().findByMovie(matrix)!!
        assertEquals("603" to "vKQi3bBA1y8", stored.externalId to stored.trailerKey)
        assertEquals(listOf(Source.IMDB, Source.TMDB), orm.repository<MovieSourceRepository>().findSources(matrix).map { it.source }.sorted())

        // The same export again finds The Matrix by its TMDB source, and asks only about the one TMDB lacks.
        requests.clear()
        runBlocking { service.importHistory(user, rows) }
        assertEquals(listOf("/3/movie/999999"), requests.map { it.substringBefore('?') })
    }

    @Test
    fun `a visitor who did not sign in never makes TMDB be asked`(orm: ORMTemplate) {
        val tmdb = tmdbService(orm, client())
        val visitor = orm.insertUser("visitor")
        val rows = listOf(ExportRow(Source.TMDB, "603", LocalDate.of(2024, 2, 11), 9))

        // Nothing stored yet: no trailer, no platforms, and an import that cannot place the TMDB id.
        assertNull(runBlocking { tmdb.trailer("tt0133093", askTmdb = false) })
        assertTrue(runBlocking { tmdb.watchProviders("tt0133093", "NL", askTmdb = false) }.isEmpty())
        assertEquals(1, runBlocking { movieSeenService(orm, client()).importHistory(visitor, rows) }.skipped)
        assertTrue(requests.isEmpty())

        // Once a signed-in visitor's request stored the answer, everyone reads it, still without a call.
        runBlocking { tmdb.trailer("tt0133093", askTmdb = true) }
        requests.clear()
        assertEquals("vKQi3bBA1y8", runBlocking { tmdb.trailer("tt0133093", askTmdb = false) })
        assertEquals(listOf("Disney Plus", "Netflix"), runBlocking { tmdb.watchProviders("tt0133093", "NL", askTmdb = false) }.map { it.name })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a movie's trailer comes from TMDB once, and is stored, none included`(orm: ORMTemplate) {
        val tmdb = tmdbService(orm, client())

        // The official English trailer, ahead of the teaser and of the video on another site.
        assertEquals("vKQi3bBA1y8", runBlocking { tmdb.trailer("tt0133093", askTmdb = true) })
        assertEquals(listOf("/3/find/tt0133093", "/3/movie/603"), requests.map { it.substringBefore('?') })
        requests.clear()
        assertEquals("vKQi3bBA1y8", runBlocking { tmdb.trailer("tt0133093", askTmdb = true) })
        assertTrue(requests.isEmpty())

        // A movie TMDB does not have is remembered as such.
        assertNull(runBlocking { tmdb.trailer("tt0111161", askTmdb = true) })
        assertNull(runBlocking { tmdb.trailer("tt0111161", askTmdb = true) })
        assertEquals(listOf("/3/find/tt0111161"), requests.map { it.substringBefore('?') })

        // Without a key there are no trailers, and TMDB is not asked.
        requests.clear()
        assertNull(runBlocking { tmdbService(orm, client(apiKey = null)).trailer("tt0068646", askTmdb = true) })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `what TMDB answered is kept six months, then fetched again or removed`(orm: ORMTemplate) {
        val tmdb = tmdbService(orm, client())
        val stored = orm.repository<TmdbSourceRepository>()
        val matrix = orm.repository<MovieRepository>().getById("tt0133093")
        val longAgo = Instant.parse("2026-01-01T00:00:00Z")
        runBlocking { tmdb.trailer("tt0133093", askTmdb = true, now = longAgo) }

        // Five months on, the page still reads the stored trailer; seven months on, it asks TMDB again.
        requests.clear()
        runBlocking { tmdb.trailer("tt0133093", askTmdb = true, now = longAgo.plusSeconds(150L * 24 * 3600)) }
        assertTrue(requests.isEmpty())
        runBlocking { tmdb.trailer("tt0133093", askTmdb = true, now = longAgo.plusSeconds(210L * 24 * 3600)) }
        assertEquals(2, requests.size)

        // Unasked for six months past that lookup, it is gone.
        runBlocking { tmdb.removeExpired(now = longAgo.plusSeconds(400L * 24 * 3600)) }
        assertNull(stored.findByMovie(matrix))
    }

    @Test
    fun `where a movie plays comes per country with the trailer, kept a week`(orm: ORMTemplate) {
        val tmdb = tmdbService(orm, client())
        val now = Instant.parse("2026-10-01T00:00:00Z")

        // Streaming first, in TMDB's order, then free and with ads, each platform once; renting, a service
        // resold through Amazon and a plan with ads are left out.
        assertEquals(listOf("Disney Plus", "Netflix"), runBlocking { tmdb.watchProviders("tt0133093", "NL", askTmdb = true, now = now) }.map { it.name })
        assertEquals(listOf("/disney.jpg", "/netflix.jpg"), runBlocking { tmdb.watchProviders("tt0133093", "NL", askTmdb = true, now = now) }.map { it.logoPath })
        assertEquals(listOf("Tubi TV"), runBlocking { tmdb.watchProviders("tt0133093", "US", askTmdb = true, now = now) }.map { it.name })
        // Only to buy is nowhere to stream, and a country TMDB does not name has none either.
        assertTrue(runBlocking { tmdb.watchProviders("tt0133093", "DE", askTmdb = true, now = now) }.isEmpty())
        assertTrue(runBlocking { tmdb.watchProviders("tt0133093", "BE", askTmdb = true, now = now) }.isEmpty())
        // One lookup answered all of it, the trailer included.
        assertEquals(listOf("/3/find/tt0133093", "/3/movie/603"), requests.map { it.substringBefore('?') })
        assertTrue(requests.last().contains("append_to_response=videos,watch/providers"))
        requests.clear()
        assertEquals("vKQi3bBA1y8", runBlocking { tmdb.trailer("tt0133093", askTmdb = true, now = now) })
        assertTrue(requests.isEmpty())

        // Six days on, the stored platforms; eight days on, TMDB again, while the trailer alone would wait.
        runBlocking { tmdb.watchProviders("tt0133093", "NL", askTmdb = true, now = now.plusSeconds(6L * 24 * 3600)) }
        assertTrue(requests.isEmpty())
        runBlocking { tmdb.trailer("tt0133093", askTmdb = true, now = now.plusSeconds(8L * 24 * 3600)) }
        assertTrue(requests.isEmpty())
        runBlocking { tmdb.watchProviders("tt0133093", "NL", askTmdb = true, now = now.plusSeconds(8L * 24 * 3600)) }
        assertEquals(2, requests.size)
    }

    @Test
    fun `a user has services among the country's, and For You keeps to them`(orm: ORMTemplate) {
        val streaming = StreamingService(orm.repository(), orm.repository(), orm.repository(), orm.repository(), tmdbService(orm, client()))
        val alice = orm.insertUser("alice", name = "Alice")
        val netherlands = Countries.of("NL")!!

        // The services of the country, most watched there first; the one resold through Amazon is left out.
        assertEquals(listOf("Netflix", "Disney Plus"), runBlocking { streaming.servicesIn(netherlands) }.map { it.name })
        // Only services of the country can be had.
        runBlocking { streaming.updateServices(alice, netherlands, setOf(337, 1899, 999)) }
        assertEquals(listOf("Disney Plus"), runBlocking { streaming.servicesOf(alice) }.map { it.name })
        // Alice left her country to the browser; her services keep the one they were chosen in.
        assertEquals("NL", orm.repository<UserRepository>().getById("alice").country)

        // The Matrix streams on Disney Plus there; TMDB does not know The Shawshank Redemption.
        val byMovie = runBlocking { streaming.servicesByMovie(alice, netherlands, listOf("tt0133093", "tt0111161")) }
        assertEquals(mapOf("tt0133093" to listOf("Disney Plus")), byMovie.mapValues { (_, services) -> services.map { it.name } })
        val movies = orm.repository<MovieRepository>()
        val picks = listOf(RecommendedMovie(movies.getById("tt0111161"), emptyList()), RecommendedMovie(movies.getById("tt0133093"), emptyList()))
        assertEquals(listOf("tt0133093"), runBlocking { streaming.keepToServices(alice, netherlands, picks) }.map { it.movie.id })
    }

    @Test
    fun `the share of a user's services counts the watched movies on each, and leaves out the ones on none`(orm: ORMTemplate) {
        val streaming = StreamingService(orm.repository(), orm.repository(), orm.repository(), orm.repository(), tmdbService(orm, client()))
        val alice = orm.insertUser("alice", name = "Alice")
        val netherlands = Countries.of("NL")!!
        runBlocking { streaming.updateServices(alice, netherlands, setOf(8, 337)) }
        val movies = orm.repository<MovieRepository>()
        val seenAt = Instant.parse("2026-10-01T20:00:00Z")
        // The Matrix streams on both of Alice's services there; TMDB does not know The Shawshank Redemption.
        orm.repository<MovieSeenRepository>().insert(listOf(MovieSeen(alice, movies.getById("tt0133093"), seenAt), MovieSeen(alice, movies.getById("tt0111161"), seenAt)))

        val shares = runBlocking { streaming.sharesOfWatched(alice, netherlands) }

        assertEquals(listOf("Netflix" to 1, "Disney Plus" to 1), shares.shares.map { it.service.name to it.movies }.sortedByDescending { it.first })
        assertEquals(listOf(50, 50), shares.shares.map { it.percent })
        assertEquals(Triple(1, 2, 2), Triple(shares.counted, shares.known, shares.watched))
    }

    private companion object {
        val NETHERLANDS = """
            {"results":[
              {"provider_id":337,"provider_name":"Disney Plus","logo_path":"/disney.jpg","display_priority":9,"display_priorities":{"NL":2}},
              {"provider_id":1899,"provider_name":"HBO Max Amazon Channel","logo_path":"/hbo-amazon.jpg","display_priority":1,"display_priorities":{"NL":3}},
              {"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.jpg","display_priority":5,"display_priorities":{"NL":1}}
            ]}
        """.trimIndent()

        val MATRIX = """
            {"id":603,"imdb_id":"tt0133093","title":"The Matrix","videos":{"results":[
              {"site":"YouTube","type":"Teaser","key":"teaser1","official":true,"iso_639_1":"en","published_at":"2021-01-01T00:00:00.000Z"},
              {"site":"Vimeo","type":"Trailer","key":"vimeo1","official":true,"iso_639_1":"en","published_at":"2022-01-01T00:00:00.000Z"},
              {"site":"YouTube","type":"Trailer","key":"fanmade","official":false,"iso_639_1":"en","published_at":"2023-01-01T00:00:00.000Z"},
              {"site":"YouTube","type":"Trailer","key":"vKQi3bBA1y8","official":true,"iso_639_1":"en","published_at":"2019-05-02T00:00:00.000Z"}
            ]},"watch/providers":{"results":{
              "NL":{"link":"https://www.themoviedb.org/movie/603/watch?locale=NL",
                "flatrate":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.jpg","display_priority":2},
                            {"provider_id":337,"provider_name":"Disney Plus","logo_path":"/disney.jpg","display_priority":1},
                            {"provider_id":1899,"provider_name":"HBO Max Amazon Channel","logo_path":"/hbo-amazon.jpg","display_priority":3}],
                "ads":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.jpg","display_priority":2},
                       {"provider_id":1796,"provider_name":"Netflix Standard with Ads","logo_path":"/netflix-ads.jpg","display_priority":3}],
                "rent":[{"provider_id":2,"provider_name":"Apple TV","logo_path":"/apple.jpg","display_priority":0}]},
              "US":{"link":"https://www.themoviedb.org/movie/603/watch?locale=US",
                "free":[{"provider_id":73,"provider_name":"Tubi TV","logo_path":"/tubi.jpg","display_priority":5}]},
              "DE":{"link":"https://www.themoviedb.org/movie/603/watch?locale=DE",
                "buy":[{"provider_id":2,"provider_name":"Apple TV","logo_path":"/apple.jpg","display_priority":0}]}
            }}}
        """.trimIndent()
    }
}
