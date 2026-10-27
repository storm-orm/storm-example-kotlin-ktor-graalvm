package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.DailyGame
import st.orm.demo.imdb.model.DailyPuzzle
import st.orm.demo.imdb.model.DailyPuzzleData
import st.orm.demo.imdb.model.DailyPuzzlePk
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.DailyPlayRepository
import st.orm.demo.imdb.repository.DailyPuzzleRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.ConnectionGroup
import st.orm.demo.imdb.service.DailyPuzzleService
import st.orm.demo.imdb.service.GridGuess
import st.orm.demo.imdb.service.OddPuzzle
import st.orm.demo.imdb.service.PuzzleFactory
import st.orm.demo.imdb.service.Trait
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.LocalDate
import kotlin.random.Random

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class DailyPuzzleServiceTest {

    private fun factory(orm: ORMTemplate) = PuzzleFactory(orm.repository<PrincipalRepository>())

    private fun service(orm: ORMTemplate) = DailyPuzzleService(
        factory(orm),
        orm.repository<DailyPuzzleRepository>(),
        orm.repository<DailyPlayRepository>(),
        orm.repository<RatingRepository>(),
        orm.repository<MovieRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<UserRepository>()
    )

    private val day = LocalDate.parse("2026-10-27")

    private fun trait(key: String, level: Int) = Trait(key, key, level)

    @Test
    fun `a movie's traits are its director and its stars, nothing else`(orm: ORMTemplate) {
        val movies = orm.repository<MovieRepository>().findAllById(listOf("tt0133093", "tt0068646"))
        val traits = factory(orm).traitsOf(movies)
        assertEquals(setOf("Directed by Lana Wachowski", "With Keanu Reeves", "With Laurence Fishburne"), traits.getValue("tt0133093").map { it.label }.toSet())
        assertEquals(setOf("With Al Pacino"), traits.getValue("tt0068646").map { it.label }.toSet())
    }

    @Test
    fun `a movie and its sequel are of one series`() {
        fun same(a: String, b: String) = PuzzleFactory.sameSeries(PuzzleFactory.wordsOf(a), PuzzleFactory.wordsOf(b))
        assertTrue(same("Dune", "Dune: Part Two"))
        assertTrue(same("Ant-Man", "Ant-Man and the Wasp"))
        assertTrue(same("Toy Story", "Toy Story 3"))
        assertTrue(same("The Matrix", "The Matrix Reloaded"))
        assertTrue(same("Rocky", "Rocky II"))
        assertTrue(same("Pirates of the Caribbean: The Curse of the Black Pearl", "Pirates of the Caribbean: On Stranger Tides"))
        assertTrue(same("The Bourne Supremacy", "The Bourne Ultimatum"))
        assertTrue(same("Rise of the Planet of the Apes", "Dawn of the Planet of the Apes"))
        assertTrue(same("X-Men", "X2: X-Men United"))
        assertFalse(same("The Shining", "The Usual Suspects"))
        assertFalse(same("Inception", "Interstellar"))
    }

    @Test
    fun `an odd one out never holds a movie with its sequel`(orm: ORMTemplate) {
        val nolan = trait("dir:villeneuve", 1)
        val movies = listOf("Dune", "Dune: Part Two", "Arrival", "Sicario", "Prisoners", "Heat", "Up").mapIndexed { i, title -> Movie("m$i", title, title, 2000, 120) }
        val traits = movies.associate { it.id to (if (it.primaryTitle != "Heat" && it.primaryTitle != "Up") setOf(nolan) else emptySet()) }
        repeat(20) { seed ->
            val titles = factory(orm).oddOneOut(movies, traits, Random(seed), count = 1).single().movies.map { id -> movies.first { it.id == id }.primaryTitle }
            assertFalse("Dune" in titles && "Dune: Part Two" in titles, "$titles")
        }
    }

    @Test
    fun `an odd one out has one answer only, the three sharing nothing else`(orm: ORMTemplate) {
        val movies = (1..12).map { Movie("m$it", "Title$it", "Title$it", 2000, 120) }
        val nolan = trait("dir:nolan", 1)
        val y1994 = trait("year:1994", 3)
        val traits = movies.associate { it.id to mutableSetOf<Trait>() }.also {
            // m1..m4 share a director: three of them make a puzzle, the fourth must not be its odd one.
            (1..4).forEach { i -> it.getValue("m$i") += nolan }
            // m5 and m6 share a year with m1: an odd one of those would let m1, m5, m6 share it too.
            listOf(1, 5, 6).forEach { i -> it.getValue("m$i") += y1994 }
        }
        repeat(20) { seed ->
            val puzzle = factory(orm)
                .oddOneOut(movies, traits, Random(seed), count = 1).single()
            val three = puzzle.movies - puzzle.odd
            assertTrue(three.all { puzzle.trait in traits.getValue(it) })
            assertFalse(puzzle.trait in traits.getValue(puzzle.odd))
            val shared = puzzle.movies.flatMap { traits.getValue(it) }.groupingBy { it }.eachCount().filterValues { it >= 3 }.keys
            assertEquals(setOf(puzzle.trait), shared)
        }
    }

    @Test
    fun `a connections grid has four groups that no other movie of the sixteen fits`(orm: ORMTemplate) {
        val levels = listOf(trait("act:b", 2), trait("dir:a", 1), trait("act:c", 2), trait("dir:d", 1))
        val movies = (1..20).map { Movie("m$it", "Title$it", "Title$it", 2000, 120) }
        val traits = movies.associate { it.id to mutableSetOf<Trait>() }.also {
            levels.forEachIndexed { g, trait -> (1..5).forEach { i -> it.getValue("m${g * 5 + i}") += trait } }
        }
        val groups = factory(orm).connections(movies, traits, Random(7))
        assertNotNull(groups)
        groups!!
        // The directors first.
        assertEquals(listOf(1, 1, 2, 2), groups.map { it.trait.level })
        val sixteen = groups.flatMap { it.movies }
        assertEquals(16, sixteen.toSet().size)
        groups.forEach { group -> assertEquals(4, sixteen.count { group.trait in traits.getValue(it) }) }
    }

    @Test
    fun `an odd one out is answered puzzle by puzzle, a point for each odd one found`(orm: ORMTemplate) {
        val odd = listOf(
            OddPuzzle(listOf("tt0133093", "tt0234215", "tt0111161", "tt0110912"), "tt0111161", trait("act:keanu", 2)),
            OddPuzzle(listOf("tt0068646", "tt0211915", "tt0133093", "tt0110912"), "tt0211915", trait("lang:fr", 3))
        )
        orm.repository<DailyPuzzleRepository>().insert(DailyPuzzle(DailyPuzzlePk(day, DailyGame.ODD_ONE_OUT, ""), DailyPuzzleData(odd = odd)))
        val service = service(orm)
        val alice = orm.insertUser("alice-odd", name = "Alice")

        var view = runBlocking { service.oddOf(alice, day) }
        assertEquals(1L, view.number)
        assertEquals(0, view.index)
        assertEquals(4, view.current.size)

        runBlocking { service.answerOdd(alice, "tt0111161", day) }
        view = runBlocking { service.oddOf(alice, day) }
        assertEquals(1, view.score)
        assertEquals("tt0111161", view.last!!.odd.id)

        // A movie that is not in the puzzle at hand counts for nothing.
        runBlocking { service.answerOdd(alice, "tt0234215", day) }
        assertEquals(1, runBlocking { service.oddOf(alice, day) }.index)

        runBlocking { service.answerOdd(alice, "tt0068646", day) }
        view = runBlocking { service.oddOf(alice, day) }
        assertTrue(view.finished)
        assertEquals(1, view.score)
        assertEquals(2, view.results.size)
        assertEquals("tt0068646", view.results[1].picked!!.id)
    }

    @Test
    fun `connections finds a group, says one away, and ends after four mistakes`(orm: ORMTemplate) {
        val movieRepository = orm.repository<MovieRepository>()
        movieRepository.insert((1..10).map { Movie("tt90000%02d".format(it), "Extra $it", "Extra $it", 2000 + it, 100) })
        val ids = listOf("tt0133093", "tt0234215", "tt0111161", "tt0110912", "tt0068646", "tt0211915") + (1..10).map { "tt90000%02d".format(it) }
        val groups = ids.chunked(4).mapIndexed { g, four -> ConnectionGroup(four, trait("group:$g", g + 1)) }
        orm.repository<DailyPuzzleRepository>().insert(DailyPuzzle(DailyPuzzlePk(day, DailyGame.CONNECTIONS, ""), DailyPuzzleData(groups = groups)))
        val service = service(orm)
        val alice = orm.insertUser("alice-grid", name = "Alice")

        assertEquals(16, runBlocking { service.connectionsOf(alice, day) }.remaining.size)
        assertEquals(GridGuess.NOT_FOUR, runBlocking { service.guessConnections(alice, ids.take(3).toSet(), day) })
        assertEquals(GridGuess.RIGHT, runBlocking { service.guessConnections(alice, groups[0].movies.toSet(), day) })
        var view = runBlocking { service.connectionsOf(alice, day) }
        assertEquals(listOf("group:0"), view.found.map { it.label })
        assertEquals(12, view.remaining.size)

        val oneAway = (groups[1].movies.take(3) + groups[2].movies.first()).toSet()
        assertEquals(GridGuess.ONE_AWAY, runBlocking { service.guessConnections(alice, oneAway, day) })
        val wrong = (groups[1].movies.take(2) + groups[2].movies.take(2)).toSet()
        repeat(3) { assertEquals(GridGuess.WRONG, runBlocking { service.guessConnections(alice, wrong, day) }) }

        view = runBlocking { service.connectionsOf(alice, day) }
        assertTrue(view.finished)
        assertFalse(view.won)
        assertEquals(4, view.mistakes)
        assertEquals(4, view.all.size)
        // Over: a right guess no longer counts.
        runBlocking { service.guessConnections(alice, groups[1].movies.toSet(), day) }
        assertEquals(1, runBlocking { service.connectionsOf(alice, day) }.found.size)
    }
}
