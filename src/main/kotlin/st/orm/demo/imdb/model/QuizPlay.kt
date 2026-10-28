package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.Json
import st.orm.PK
import st.orm.Persist
import java.time.Instant
import java.time.LocalDate

data class QuizPlayPk(
    val userId: String,
    val quizOn: LocalDate
)

/**
 * A user's game of a day's quiz: the movie to guess, kept with the game so
 * a change of taste halfway does not change it, their guesses so far, as movie ids, an
 * empty one for a clue skipped, stored as JSON, and whether they guessed
 * it ([solved]) or are out of guesses ([finished] either way). The user
 * comes with the row, as a club's board names its players.
 */
data class QuizPlay(
    @PK(generation = NONE) val id: QuizPlayPk,
    @FK @Persist(insertable = false, updatable = false) val user: User,
    /** The movie to guess, the day's for the player's taste when they started. */
    @FK val movie: Movie,
    @Json val guesses: List<String>,
    val solved: Boolean,
    val finished: Boolean,
    val updatedAt: Instant
) : Entity<QuizPlayPk> {
    val day: LocalDate get() = id.quizOn
}
