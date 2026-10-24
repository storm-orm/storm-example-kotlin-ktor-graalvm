package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.insertUser
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant

/** The aggregates of the insights page: each joins the visitor's seen list and counts theirs alone. */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class InsightQueriesTest {

    /** A visitor who saw both Matrix movies and Shawshank, and another who saw The Godfather. */
    private fun seenFixture(orm: ORMTemplate): User {
        val movies = orm.repository<MovieRepository>()
        val seen = orm.repository<MovieSeenRepository>()
        val user = orm.insertUser("visitor-insights")
        val other = orm.insertUser("visitor-other")
        val at = Instant.parse("2026-07-01T10:00:00Z")
        listOf("tt0133093", "tt0234215", "tt0111161").forEach { seen.insert(MovieSeen(user, movies.getById(it), at)) }
        seen.insert(MovieSeen(other, movies.getById("tt0068646"), at))
        return user
    }

    @Test
    fun `the seen movies per decade`(orm: ORMTemplate, capture: SqlCapture) {
        val user = seenFixture(orm)
        val decades = capture.execute { orm.repository<MovieSeenRepository>().countPerDecade(user) }
        capture.printStatements("countPerDecade")
        assertEquals(listOf(DecadeMovieCount(1990, 2), DecadeMovieCount(2000, 1)), decades)
    }

    @Test
    fun `the genres of the seen movies, the most seen first`(orm: ORMTemplate, capture: SqlCapture) {
        val user = seenFixture(orm)
        val genres = capture.execute { orm.repository<MovieGenreRepository>().countSeenPerGenre(user, limit = 10) }
        capture.printStatements("countSeenPerGenre")
        assertEquals(mapOf("Action" to 2L, "Sci-Fi" to 2L, "Drama" to 1L), genres.associate { it.genre.name to it.movieCount })
        assertEquals(1L, genres.last().movieCount)
    }

    @Test
    fun `the people seen on at least two movies, per category`(orm: ORMTemplate, capture: SqlCapture) {
        val user = seenFixture(orm)
        val principals = orm.repository<PrincipalRepository>()
        val actors = capture.execute { principals.countSeenPeople(user, ACTING_CATEGORIES, minimumMovies = 2, limit = 10) }
        capture.printStatements("countSeenPeople")
        // Tim Robbins and Morgan Freeman are on one of the visitor's movies only; Al Pacino on another visitor's.
        assertEquals(setOf("Keanu Reeves" to 2L, "Laurence Fishburne" to 2L), actors.map { it.person.primaryName to it.movieCount }.toSet())
        val directors = principals.countSeenPeople(user, listOf(DIRECTOR), minimumMovies = 2, limit = 10)
        assertEquals(listOf("Lana Wachowski" to 2L), directors.map { it.person.primaryName to it.movieCount })
    }
}
