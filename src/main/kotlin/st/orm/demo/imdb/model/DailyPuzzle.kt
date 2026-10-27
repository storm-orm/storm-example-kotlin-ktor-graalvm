package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.Json
import st.orm.PK
import st.orm.demo.imdb.service.ConnectionGroup
import st.orm.demo.imdb.service.OddPuzzle
import java.time.LocalDate

/** The daily puzzles besides the quiz. */
enum class DailyGame { ODD_ONE_OUT, CONNECTIONS }

data class DailyPuzzlePk(
    val puzzleOn: LocalDate,
    val game: DailyGame,
    /** The players' taste, their movie filter as its key ([MovieFilter.key]); empty for every movie. */
    val filterKey: String
)

/** A day's puzzles of one of the games, as JSON: Odd One Out's ten, or a Connections grid's four groups. */
@Serializable
data class DailyPuzzleData(val odd: List<OddPuzzle> = emptyList(), val groups: List<ConnectionGroup> = emptyList())

/**
 * The puzzles of a daily game for a day and a taste, the same for everyone
 * who shares it: made the first time someone of that taste opens the game
 * that day, and kept, so the day's answers never change.
 */
data class DailyPuzzle(
    @PK(generation = NONE) val id: DailyPuzzlePk,
    @Json val data: DailyPuzzleData
) : Entity<DailyPuzzlePk>
