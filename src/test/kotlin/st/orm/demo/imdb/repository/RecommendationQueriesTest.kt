package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.insertUser
import st.orm.demo.imdb.model.Reason
import st.orm.demo.imdb.model.ReasonKind
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.printStatements
import st.orm.demo.imdb.repository.RecommendationRepository.Companion.bestFirst
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.math.BigDecimal
import java.time.Instant

/** The queries the recommendations are built from, each one statement by index. */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class RecommendationQueriesTest {

    @Test
    fun `the taste credits of the seeds are the leads and the director, in one query`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val principalRepository = orm.repository<PrincipalRepository>()
        val seeds = listOf(movieRepository.getById("tt0133093"), movieRepository.getById("tt0111161")).map { it.ref() }

        val credits = capture.execute { principalRepository.findTasteCredits(seeds, maxOrdering = 1) }

        capture.printStatements("findTasteCredits")
        assertEquals(1, capture.count(Operation.SELECT))
        // The Matrix's director is billed fifth and comes along regardless.
        assertEquals(setOf("Keanu Reeves", "Tim Robbins", "Lana Wachowski"), credits.map { it.person.primaryName }.toSet())
    }

    @Test
    fun `the filmographies of the favourite people carry the movie and its rating`(orm: ORMTemplate, capture: SqlCapture) {
        val principalRepository = orm.repository<PrincipalRepository>()
        val keanu = orm.repository<PersonRepository>().getById("nm0000206")

        val credits = capture.execute { principalRepository.findFilmographies(listOf(keanu.ref())) }

        capture.printStatements("findFilmographies")
        assertEquals(1, capture.count(Operation.SELECT))
        assertEquals(setOf("The Matrix", "The Matrix Reloaded"), credits.map { it.rating.movie.primaryTitle }.toSet())
        assertTrue(credits.all { it.person.id == keanu.id && it.category == "actor" })
    }

    @Test
    fun `the best-rated movies of a genre respect the vote floor`(orm: ORMTemplate, capture: SqlCapture) {
        val ratingRepository = orm.repository<RatingRepository>()
        val drama = orm.repository<GenreRepository>().findByName("Drama")!!

        val ratings = capture.execute { ratingRepository.findTopRatedInGenre(drama.ref(), minimumVoteCount = 2_100_000, filter = null, limit = 10) }

        capture.printStatements("findTopRatedInGenre")
        // Pulp Fiction has 2.2M votes, Shawshank 2.9M; The Godfather's 2.0M fall under the floor.
        assertEquals(listOf("The Shawshank Redemption", "Pulp Fiction"), ratings.map { it.movie.primaryTitle })
    }

    @Test
    fun `the most viewed movies since a moment are counted on the view table alone`(orm: ORMTemplate, capture: SqlCapture) {
        val movieViewRepository = orm.repository<MovieViewRepository>()

        val counts = capture.execute { movieViewRepository.findMostViewedSince(Instant.parse("2026-06-30T00:00:00Z"), limit = 10) }
        val none = movieViewRepository.findMostViewedSince(Instant.parse("2026-07-02T00:00:00Z"), limit = 10)

        capture.printStatements("findMostViewedSince")
        val statement = capture.statements().single().statement()
        assertTrue(statement.contains("COUNT(*)") && !statement.contains("JOIN"))
        assertEquals(listOf("tt0133093" to 2L, "tt0111161" to 1L), counts.map { it.movie.id() to it.viewCount })
        assertTrue(none.isEmpty())
    }

    @Test
    fun `counting picks as shown skips the rows a recomputation removed meanwhile`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val recommendationRepository = orm.repository<RecommendationRepository>()
        val user = orm.insertUser("visitor-shown-race")
        val now = Instant.now()
        val reasons = listOf(Reason(ReasonKind.RATING, "Rated 9.3 by 2.9M voters"))
        val kept = Recommendation(user, movieRepository.getById("tt0111161"), BigDecimal("2.5"), null, reasons, now)
        val replaced = Recommendation(user, movieRepository.getById("tt0068646"), BigDecimal("2.4"), null, reasons, now)
        recommendationRepository.insert(listOf(kept, replaced))
        // The page read both; a recomputation removed one before the page counted them as shown.
        recommendationRepository.remove(replaced)

        capture.clear()
        capture.record { recommendationRepository.recordShown(user, listOf(kept, replaced), now) }

        capture.printStatements("recordShown")
        assertTrue(capture.statements().first().statement().contains("FOR UPDATE"))
        assertEquals(1, capture.count(Operation.UPDATE))
        assertEquals(listOf("tt0111161" to 1), recommendationRepository.findAll(user).map { it.movie.id to it.shownCount })
    }

    @Test
    fun `a user's recommendations are replaced as a whole and scroll best first`(orm: ORMTemplate, capture: SqlCapture) {
        val movieRepository = orm.repository<MovieRepository>()
        val recommendationRepository = orm.repository<RecommendationRepository>()
        val user = orm.insertUser("visitor-rows")
        val now = Instant.now()
        val reasons = listOf(Reason(ReasonKind.RATING, "Rated 9.3 by 2.9M voters"))
        recommendationRepository.replace(user, listOf(
            Recommendation(user, movieRepository.getById("tt0111161"), BigDecimal("2.5"), null, reasons, now),
            Recommendation(user, movieRepository.getById("tt0068646"), BigDecimal("2.4"), movieRepository.getById("tt0110912"), reasons, now)
        ))

        capture.clear()
        capture.record {
            recommendationRepository.replace(user, listOf(
                Recommendation(user, movieRepository.getById("tt0234215"), BigDecimal("3.1"), movieRepository.getById("tt0133093"), reasons, now),
                Recommendation(user, movieRepository.getById("tt0111161"), BigDecimal("0.9"), null, reasons, now)
            ))
        }
        val window = capture.execute { recommendationRepository.scroll(user, bestFirst(1)) }
        capture.printStatements("replaceAndScroll")

        assertEquals(1, capture.count(Operation.DELETE))
        assertEquals(1, capture.count(Operation.INSERT))
        assertTrue(capture.statements().last().statement().contains("ORDER BY r.score DESC, r.movie_id"))
        assertEquals(listOf("The Matrix Reloaded"), window.content().map { it.movie.primaryTitle })
        assertEquals("The Matrix", window.content().single().seedMovie?.primaryTitle)
        assertEquals(reasons, window.content().single().reasons)
        val next = recommendationRepository.scroll(user, window.next()!!)
        assertEquals(listOf("The Shawshank Redemption"), next.content().map { it.movie.primaryTitle })
        assertEquals(2, recommendationRepository.findAll(user).size)
    }
}
