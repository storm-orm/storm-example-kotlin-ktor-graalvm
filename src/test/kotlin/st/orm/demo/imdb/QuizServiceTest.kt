package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.QuizDayRepository
import st.orm.demo.imdb.repository.QuizPlayRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.QuizService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.StormTest
import java.time.Instant
import java.time.LocalDate

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class QuizServiceTest {

    private fun service(orm: ORMTemplate) = QuizService(
        orm.repository<QuizDayRepository>(),
        orm.repository<QuizPlayRepository>(),
        orm.repository<RatingRepository>(),
        orm.repository<MovieRepository>(),
        orm.repository<MovieGenreRepository>(),
        orm.repository<MovieLanguageRepository>(),
        orm.repository<MovieCountryRepository>(),
        orm.repository<PrincipalRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<FriendshipRepository>(),
        orm.repository<UserRepository>()
    )

    private val day = LocalDate.parse("2026-10-27")

    /** The Matrix gets a third actor: the only movie of the fixture with every clue, so it is the day's. */
    private fun fullCast(orm: ORMTemplate) {
        orm.repository<PrincipalRepository>().insert(
            Principal(orm.repository<MovieRepository>().getById("tt0133093").ref(), 3, orm.repository<PersonRepository>().getById("nm0000151"), "actor", "Somebody")
        )
    }

    @Test
    fun `a day's quiz is one movie for everyone, a clue more with every miss, until it is guessed`(orm: ORMTemplate) {
        fullCast(orm)
        val service = service(orm)
        val alice = orm.insertUser("alice-quiz", name = "Alice")
        val bob = orm.insertUser("bob-quiz")

        var quiz = runBlocking { service.quizOf(alice, day) }
        assertEquals(2, quiz.number)
        assertEquals(listOf("1999 · Action, Sci-Fi"), quiz.clues.mapNotNull { it.text })
        assertNull(quiz.answer)
        // Bob gets the same movie.
        assertEquals(quiz.clues.first().text, runBlocking { service.quizOf(bob, day) }.clues.first().text)

        runBlocking { service.guess(alice, "tt0234215", day) }
        runBlocking { service.guess(alice, null, day) }
        quiz = runBlocking { service.quizOf(alice, day) }
        assertEquals(3, quiz.clues.count { it.text != null })
        assertEquals("Lana Wachowski", quiz.clues[2].text)
        assertFalse(quiz.finished)

        runBlocking { service.guess(alice, "tt0133093", day) }
        quiz = runBlocking { service.quizOf(alice, day) }
        assertTrue(quiz.solved)
        assertTrue(quiz.finished)
        assertEquals("The Matrix", quiz.answer!!.primaryTitle)
        assertEquals("🟥⬜🟩", quiz.squares)
        assertEquals("Storm Movies quiz #2 · 3/6\n🟥⬜🟩", quiz.shareText)
        assertEquals(6, quiz.clues.count { it.text != null })
        assertEquals(1, quiz.streak)
        // A finished game takes no more guesses.
        runBlocking { service.guess(alice, "tt0068646", day) }
        assertEquals(3, runBlocking { service.quizOf(alice, day) }.guesses.size)
    }

    @Test
    fun `six misses end the game, and club members see each other's scores`(orm: ORMTemplate) {
        fullCast(orm)
        val service = service(orm)
        val alice = orm.insertUser("alice-board", name = "Alice")
        val bob = orm.insertUser("bob-board", name = "Bob")
        val now = Instant.parse("2026-10-27T09:00:00Z")
        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(listOf(ClubMember(club, alice, true, now), ClubMember(club, bob, false, now)))

        repeat(6) { runBlocking { service.guess(bob, null, day) } }
        val bobs = runBlocking { service.quizOf(bob, day) }
        assertTrue(bobs.finished)
        assertFalse(bobs.solved)
        assertNotNull(bobs.answer)
        assertEquals("Storm Movies quiz #2 · X/6\n⬜⬜⬜⬜⬜⬜", bobs.shareText)
        // Alice has not played: her clubs' board waits till she finishes, but the club page shows Bob's game.
        assertTrue(runBlocking { service.quizOf(alice, day) }.boards.isEmpty())
        assertEquals(listOf("Bob" to false), runBlocking { service.boardOf(club.id, alice, day) }.map { it.name to it.solved })

        runBlocking { service.guess(alice, "tt0133093", day) }
        val board = runBlocking { service.quizOf(alice, day) }.boards.single()
        assertEquals("Office", board.club)
        assertEquals(listOf("You" to 1, "Bob" to 6), board.rows.map { it.name to it.guesses })
    }

    @Test
    fun `each taste gets its own movie of the day, among the movies its filter lets through`(orm: ORMTemplate) {
        fullCast(orm)
        val amelie = orm.repository<MovieRepository>().getById("tt0211915")
        orm.repository<MovieLanguageRepository>().insert(MovieLanguage(amelie.ref(), "fr"))
        val french = orm.insertUser("french-quiz", name = "Amélie fan")
        orm.repository<UserRepository>().update(french.copy(movieFilter = MovieFilter(languages = setOf("fr"))))
        val service = service(orm)

        val frenchQuiz = runBlocking { service.quizOf(orm.repository<UserRepository>().getById("french-quiz"), day) }
        assertEquals("2001", frenchQuiz.clues.first().text)
        val everyone = runBlocking { service.quizOf(orm.insertUser("anyone-quiz"), day) }
        assertEquals("1999 · Action, Sci-Fi", everyone.clues.first().text)
    }

    @Test
    fun `friends see each other's scores, and yesterday's movie is told the next day`(orm: ORMTemplate) {
        fullCast(orm)
        val service = service(orm)
        val alice = orm.insertUser("alice-friends-quiz", name = "Alice")
        val bob = orm.insertUser("bob-friends-quiz", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, Instant.parse("2026-10-01T00:00:00Z"))
        runBlocking { service.guess(bob, "tt0133093", day) }
        runBlocking { service.guess(alice, null, day) }
        runBlocking { service.guess(alice, "tt0133093", day) }

        val board = runBlocking { service.quizOf(alice, day) }.boards.single()
        assertEquals("Your friends", board.club)
        assertEquals(listOf("Bob" to 1, "You" to 2), board.rows.map { it.name to it.guesses })

        assertNull(runBlocking { service.yesterdayOf(alice, day) })
        val yesterday = runBlocking { service.yesterdayOf(alice, day.plusDays(1)) }!!
        assertEquals("The Matrix", yesterday.movie.primaryTitle)
        assertEquals(2L, yesterday.played)
        assertEquals(2L, yesterday.solved)
    }
}
