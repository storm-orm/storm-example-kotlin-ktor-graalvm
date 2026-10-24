package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.insertUser
import st.orm.demo.imdb.model.CheckIn
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
    fun `the seen movies are read as ids, from the seen list alone`(orm: ORMTemplate, capture: SqlCapture) {
        val user = seenFixture(orm)
        val seen = capture.execute { orm.repository<MovieSeenRepository>().findSeenMovies(user) }
        capture.printStatements("findSeenMovies")
        assertEquals(setOf("tt0133093", "tt0234215", "tt0111161"), seen.map { it.id() }.toSet())
        // The movie is its foreign key: no join to the movie table.
        assertFalse(capture.statements().single().statement().contains("JOIN"))
    }

    @Test
    fun `the seen movies come with their ratings in one query`(orm: ORMTemplate, capture: SqlCapture) {
        val user = seenFixture(orm)
        val rows = capture.execute { orm.repository<MovieSeenRepository>().findAllWithRatings(user) }
        capture.printStatements("findAllWithRatings")
        assertEquals(1, capture.count())
        assertEquals(
            mapOf("The Matrix" to 8.7, "The Matrix Reloaded" to 7.2, "The Shawshank Redemption" to 9.3),
            rows.associate { it.seen.movie.primaryTitle to it.rating!!.averageRating.toDouble() }
        )
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
    fun `the actors and directors seen on at least two movies, from one count`(orm: ORMTemplate, capture: SqlCapture) {
        val user = seenFixture(orm)
        val favourites = capture.execute { orm.repository<PrincipalRepository>().findSeenFavourites(user, minimumMovies = 2, limit = 10) }
        capture.printStatements("findSeenFavourites")
        // One count over both categories, then the people who made either list.
        assertEquals(2, capture.count())
        // Tim Robbins and Morgan Freeman are on one of the visitor's movies only; Al Pacino on another visitor's.
        assertEquals(setOf("Keanu Reeves" to 2L, "Laurence Fishburne" to 2L), favourites.actors.map { it.person.primaryName to it.movieCount }.toSet())
        assertEquals(listOf("Lana Wachowski" to 2L), favourites.directors.map { it.person.primaryName to it.movieCount })
    }


    @Test
    fun `the catch-up page scrolls the most voted movies first`(orm: ORMTemplate) {
        val ratings = orm.repository<RatingRepository>()
        val first = ratings.scrollMostVoted(null, null, RatingRepository.mostVotedFirst(2))
        assertEquals(listOf("The Shawshank Redemption", "Pulp Fiction"), first.content().map { it.movie.primaryTitle })
        val next = ratings.scrollMostVoted(null, null, RatingRepository.mostVotedFirst(2).from(first.nextCursor()!!))
        // The Matrix and The Godfather tie at two million votes: the movie's id decides.
        assertEquals(listOf("The Godfather", "The Matrix"), next.content().map { it.movie.primaryTitle })
        // Of one genre: the Sci-Fi movies only.
        val sciFi = orm.repository<GenreRepository>().findByName("Sci-Fi")
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), ratings.scrollMostVoted(sciFi, null, RatingRepository.mostVotedFirst(10)).content().map { it.movie.primaryTitle })
    }

    @Test
    fun `the catch-up page can leave out the movies a user marked, by an anti-join`(orm: ORMTemplate, capture: SqlCapture) {
        val user = seenFixture(orm)
        val unmarked = capture.execute { orm.repository<RatingRepository>().scrollMostVoted(null, user, RatingRepository.mostVotedFirst(10)) }
        capture.printStatements("scrollMostVoted(unseenBy)")
        // The visitor saw both Matrix movies and Shawshank; another visitor's Godfather still counts as unmarked here.
        assertEquals(listOf("Pulp Fiction", "The Godfather", "Amélie"), unmarked.content().map { it.movie.primaryTitle })
        assertTrue(capture.statements().single().statement().contains("NOT EXISTS"))
    }


    @Test
    fun `the seen page scrolls the latest first, and the check-ins page every watch`(orm: ORMTemplate) {
        val movies = orm.repository<MovieRepository>()
        val seen = orm.repository<MovieSeenRepository>()
        val checkIns = orm.repository<CheckInRepository>()
        val user = orm.insertUser("visitor-scroll")
        seen.insert(MovieSeen(user, movies.getById("tt0133093"), Instant.parse("2026-10-01T10:00:00Z")))
        seen.insert(MovieSeen(user, movies.getById("tt0111161"), Instant.parse("2026-10-03T10:00:00Z")))
        seen.insert(MovieSeen(user, movies.getById("tt0068646"), Instant.parse("2026-10-05T10:00:00Z")))
        // The Matrix watched twice, Shawshank once, The Godfather seen without a check-in.
        listOf("2026-09-01T12:00:00Z", "2026-10-01T10:00:00Z").forEach { checkIns.insert(CheckIn(user, movies.getById("tt0133093"), Instant.parse(it))) }
        checkIns.insert(CheckIn(user, movies.getById("tt0111161"), Instant.parse("2026-10-03T10:00:00Z")))

        val first = seen.scroll(user, MovieSeenRepository.newestFirst(2))
        assertEquals(listOf("The Godfather", "The Shawshank Redemption"), first.content().map { it.movie.primaryTitle })
        val next = seen.scroll(user, MovieSeenRepository.newestFirst(2).from(first.nextCursor()!!))
        assertEquals(listOf("The Matrix"), next.content().map { it.movie.primaryTitle })
        assertEquals(3, seen.countFor(user))

        val watches = checkIns.scroll(user, CheckInRepository.newestFirst(10))
        assertEquals(listOf("The Shawshank Redemption", "The Matrix", "The Matrix"), watches.content().map { it.movie.primaryTitle })
        assertEquals(mapOf("tt0133093" to Instant.parse("2026-10-01T10:00:00Z"), "tt0111161" to Instant.parse("2026-10-03T10:00:00Z")),
            checkIns.findLatestPerMovie(user).associate { it.movie.id() to it.watchedAt })
    }

}
