package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.Json
import st.orm.PK
import st.orm.Persist
import java.time.Instant
import java.time.LocalDate

data class DailyPlayPk(
    val userId: String,
    val puzzleOn: LocalDate,
    val game: DailyGame
)

/**
 * A user's game of a day's puzzles: the puzzles' taste, kept so a change of
 * taste halfway does not change them, the answers so far, as JSON (the movie
 * picked for each Odd One Out, the groups found in a Connections grid, in
 * order), the score, the mistakes, and whether it is over. The user comes
 * with the row, as the boards name the players.
 */
data class DailyPlay(
    @PK(generation = NONE) val id: DailyPlayPk,
    @FK @Persist(insertable = false, updatable = false) val user: User,
    val filterKey: String,
    @Json val answers: List<String>,
    val score: Int,
    val mistakes: Int,
    val finished: Boolean,
    val updatedAt: Instant
) : Entity<DailyPlayPk>
