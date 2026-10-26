package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.QuizDay
import st.orm.demo.imdb.model.QuizDayPk
import st.orm.demo.imdb.model.QuizPlay
import st.orm.demo.imdb.model.QuizPlayPk
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.DIRECTOR
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.QuizDayRepository
import st.orm.demo.imdb.repository.QuizPlayRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.ref
import st.orm.template.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** A clue of the quiz: what kind it is, for its icon, what it tells, and, once revealed, its [text]; null while it is still hidden. */
data class Clue(val kind: String, val label: String, val text: String?)

/** A guess as the quiz lists it: the movie guessed, or none for a clue skipped, and whether it was the one. */
data class Guess(val movie: Movie?, val correct: Boolean)

/** How a club's members did on a day's quiz: a row each, the ones who got it first, in fewest guesses. */
data class QuizBoard(val club: String, val rows: List<QuizScore>)

/** A member's game on a club's board: their name, as far as they share it, how many guesses, and whether they got it. */
data class QuizScore(val name: String, val avatarUrl: String?, val guesses: Int, val solved: Boolean, val you: Boolean)

/**
 * A day's quiz for a player: its number, the clues so far, the guesses,
 * and, once finished, the movie, the share line and their clubs' boards.
 */
data class QuizView(
    val number: Long,
    val clues: List<Clue>,
    val guesses: List<Guess>,
    val solved: Boolean,
    val finished: Boolean,
    /** The movie, once the game is over; null before, so the page cannot give it away. */
    val answer: Movie?,
    val streak: Int,
    val boards: List<QuizBoard>
) {
    val guessesLeft: Int get() = QuizService.MAX_GUESSES - guesses.size

    /** The game as squares, for sharing: a red one for a miss, a white one for a skip, a green one for the hit. */
    val squares: String
        get() = guesses.joinToString("") { if (it.correct) "🟩" else if (it.movie == null) "⬜" else "🟥" }

    /** The line to share: the quiz's number, the score and the squares. */
    val shareText: String
        get() = "Storm Movies quiz #$number · ${if (solved) "${guesses.size}/${QuizService.MAX_GUESSES}" else "X/${QuizService.MAX_GUESSES}"}\n$squares"
}

/**
 * The daily quiz: one movie a day, the same for everyone of a taste, to guess from six
 * clues revealed one at a time: the year and genres, where it was made,
 * the director, then the cast from the third billed to the lead. Each miss
 * or skip reveals the next clue; six misses, and the game is over. The
 * movie is one of the best known among those the player's movie filter
 * lets through, picked at random the first time anyone of that taste opens
 * the day's quiz and kept for the day ([QuizDay]); a movie is the answer
 * once. Anyone plays, signed in or not; a club's members see how the
 * others did.
 */
class QuizService(
    private val quizDayRepository: QuizDayRepository,
    private val quizPlayRepository: QuizPlayRepository,
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val movieLanguageRepository: MovieLanguageRepository,
    private val movieCountryRepository: MovieCountryRepository,
    private val principalRepository: PrincipalRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val userRepository: UserRepository
) {

    /** The player's game of the day's quiz, as the page shows it. */
    suspend fun quizOf(user: User, today: LocalDate = today()): QuizView = transaction {
        val play = quizPlayRepository.findById(QuizPlayPk(userId = user.id, quizOn = today))
        val movie = play?.movie ?: movieOf(today, user.movieFilter)
        val guesses = play?.guesses.orEmpty()
        val finished = play?.finished == true
        val guessed = movieRepository.findAllById(guesses.filter { it.isNotEmpty() }).associateBy { it.id }
        QuizView(
            number = numberOf(today),
            clues = cluesOf(movie, if (finished) MAX_GUESSES else guesses.size + 1),
            guesses = guesses.map { Guess(guessed[it], it == movie.id) },
            solved = play?.solved == true,
            finished = finished,
            answer = movie.takeIf { finished },
            streak = streakOf(user, today),
            boards = if (finished) boardsOf(user, today) else emptyList()
        )
    }

    /** Guesses the day's movie; a skip with no [movieId]. Nothing once the game is over. */
    suspend fun guess(user: User, movieId: String?, today: LocalDate = today(), now: Instant = Instant.now()) = transaction {
        val stored = userRepository.touch(user, now)
        val id = QuizPlayPk(userId = stored.id, quizOn = today)
        val play = quizPlayRepository.findById(id)
        val movie = play?.movie ?: movieOf(today, user.movieFilter)
        if (play?.finished == true) return@transaction
        val guess = movieId?.takeIf { movieRepository.existsById(it) }.orEmpty()
        if (movieId != null && guess.isEmpty()) return@transaction
        val guesses = play?.guesses.orEmpty() + guess
        val solved = guess == movie.id
        val next = QuizPlay(id, stored, movie, guesses, solved, finished = solved || guesses.size >= MAX_GUESSES, updatedAt = now)
        if (play == null) quizPlayRepository.insert(next) else quizPlayRepository.update(next)
    }

    /**
     * The day's movie for a taste: the one picked for the day, or, the first
     * time someone of that taste opens the day's quiz, one of the best-known
     * movies their [filter] lets through, not picked before, at random by
     * the day and the taste, with a director and three actors to give as
     * clues where one has them. A taste with nothing left to pick gets one
     * of everybody's.
     */
    private fun movieOf(day: LocalDate, filter: MovieFilter?): Movie {
        val key = filter?.key.orEmpty()
        quizDayRepository.findById(QuizDayPk(quizOn = day, filterKey = key))?.let { return it.movie }
        val answered = quizDayRepository.findAnswered()
        fun poolOf(filter: MovieFilter?) = ratingRepository.findBestKnown(filter, MINIMUM_VOTES, POOL).map { it.movie }.filter { it.id !in answered }
        val pool = poolOf(filter).ifEmpty { poolOf(null) }
        val shuffled = pool.shuffled(Random(day.toEpochDay() * 31 + key.hashCode()))
        val movie = shuffled.firstOrNull { cast(it).let { (directors, actors) -> directors.isNotEmpty() && actors.size >= 3 } } ?: shuffled.first()
        // Two players of a taste opening the day's quiz at once pick the same movie, so the second write changes nothing.
        quizDayRepository.upsert(QuizDay(QuizDayPk(quizOn = day, filterKey = key), movie))
        return movie
    }

    /** A movie's directors and its top three billed actors, the lead first. */
    private fun cast(movie: Movie): Pair<List<String>, List<String>> {
        val credits = principalRepository.findCast(movie)
        return credits.filter { it.category == DIRECTOR }.map { it.person.primaryName } to
            credits.filter { it.category in ACTING_CATEGORIES }.take(3).map { it.person.primaryName }
    }

    /** The six clues, the first [revealed] of them with their text. */
    private fun cluesOf(movie: Movie, revealed: Int): List<Clue> {
        val (directors, actors) = cast(movie)
        val genres = movieGenreRepository.findGenres(movie).map { it.name }
        val countries = movieCountryRepository.findByMovies(listOf(movie.ref())).mapNotNull { Countries.of(it.id.country)?.name }
        val languages = movieLanguageRepository.findLanguages(movie).map(Languages::nameOf)
        val clues = listOf(
            Clue("released", "Released", listOfNotNull(movie.startYear?.toString(), genres.joinToString(", ").ifEmpty { null }).joinToString(" · ")),
            Clue("made", "Made", listOfNotNull(
                countries.joinToString(", ").ifEmpty { null }?.let { "in $it" },
                languages.joinToString(", ").ifEmpty { null }?.let { "in $it" },
                movie.runtimeMinutes?.let { "$it minutes" }
            ).joinToString(" · ").ifEmpty { "Somewhere" }),
            Clue("director", "Directed by", directors.joinToString(", ").ifEmpty { UNKNOWN }),
            Clue("cast", "Also starring", actors.getOrNull(2) ?: UNKNOWN),
            Clue("cast", "Co-starring", actors.getOrNull(1) ?: UNKNOWN),
            Clue("lead", "Starring", actors.getOrNull(0) ?: UNKNOWN)
        )
        return clues.mapIndexed { i, clue -> if (i < revealed) clue else clue.copy(text = null) }
    }

    /** How many days in a row, up to today or yesterday, the player guessed the movie. */
    private fun streakOf(user: User, today: LocalDate): Int {
        val days = quizPlayRepository.findSolvedDays(user)
        var expected = if (days.firstOrNull() == today) today else today.minusDays(1)
        var streak = 0
        for (day in days) {
            if (day != expected) break
            streak++
            expected = expected.minusDays(1)
        }
        return streak
    }

    /** How the members of the player's clubs did today, a board per club with anyone else on it. */
    private fun boardsOf(user: User, today: LocalDate): List<QuizBoard> {
        val clubs = clubMemberRepository.findClubs(user).map { it.club }
        val members = clubMemberRepository.findMembers(clubs)
        val plays = quizPlayRepository.findFinished(members.map { it.user }.distinctBy { it.id }, today).associateBy { it.user.id }
        return members.groupBy { it.club }.mapNotNull { (club, inClub) ->
            val rows = scores(inClub, plays, user)
            QuizBoard(club.name, rows).takeIf { rows.any { !it.you } }
        }.sortedBy { it.club }
    }

    /** How the club's members did today, for its page: empty while nobody finished, or for someone not in the club. */
    suspend fun boardOf(clubId: Long, user: User, today: LocalDate = today()): List<QuizScore> = transaction(readOnly = true) {
        val membership = clubMemberRepository.findClubs(user).firstOrNull { it.club.id == clubId } ?: return@transaction emptyList()
        val members = clubMemberRepository.findMembers(listOf(membership.club))
        val plays = quizPlayRepository.findFinished(members.map { it.user }, today).associateBy { it.user.id }
        scores(members, plays, user)
    }

    /** The members who finished the day's quiz, each named as far as they share it in the club; the ones who got it first, in fewest guesses. */
    private fun scores(members: List<ClubMember>, plays: Map<String, QuizPlay>, user: User): List<QuizScore> =
        members.mapNotNull { member ->
            plays[member.user.id]?.let { play ->
                val you = member.user.id == user.id
                QuizScore(
                    name = if (you) "You" else member.user.name.takeIf { member.sharesName } ?: "A member",
                    avatarUrl = member.user.avatarUrl.takeIf { you || member.sharesName },
                    guesses = play.guesses.size,
                    solved = play.solved,
                    you = you
                )
            }
        }.sortedWith(compareByDescending<QuizScore> { it.solved }.thenBy { it.guesses })

    /** The quiz's number: the first, on launch day, is #1. */
    private fun numberOf(day: LocalDate): Long = ChronoUnit.DAYS.between(FIRST_DAY, day) + 1

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    companion object {
        const val MAX_GUESSES = 6
        /**
         * The movies the quiz picks from: the four hundred best known of a
         * taste, over a year of quizzes; for every movie, the ones with half
         * a million votes and more. None with fewer votes than the floor.
         */
        private const val POOL = 400
        private const val MINIMUM_VOTES = 25_000
        private val FIRST_DAY: LocalDate = LocalDate.of(2026, 10, 26)
        /** A clue the catalogue has nothing for: the movies picked first have all of them. */
        private const val UNKNOWN = "Nobody knows"
    }
}
