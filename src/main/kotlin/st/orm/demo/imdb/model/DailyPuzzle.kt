package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.Json
import st.orm.PK
import st.orm.demo.imdb.service.ConnectionGroup
import st.orm.demo.imdb.service.DuelPuzzle
import st.orm.demo.imdb.service.LineUpPuzzle
import st.orm.demo.imdb.service.OddPuzzle
import st.orm.demo.imdb.service.ScorePuzzle
import st.orm.demo.imdb.service.SeriesPuzzle
import java.time.LocalDate

/**
 * The daily puzzles besides the quiz: Odd One Out, Movie Connections,
 * Face/Off (which of two is rated higher), The Usual Suspects (who played
 * the part), Memento (a series in order of release) and Amadeus (who wrote
 * the score, from the player's own movies).
 */
enum class DailyGame { ODD_ONE_OUT, CONNECTIONS, FACE_OFF, USUAL_SUSPECTS, MEMENTO, AMADEUS }

data class DailyPuzzlePk(
    val puzzleOn: LocalDate,
    val game: DailyGame,
    /**
     * Whose puzzles: the players' taste, their movie filter as its key
     * ([MovieFilter.key]), empty for every movie; or, for a game made of a
     * player's own movies, the player ("user:" and their id).
     */
    val filterKey: String
)

/** A day's puzzles of one of the games, as JSON: the field of the game, the others empty. */
@Serializable
data class DailyPuzzleData(
    val odd: List<OddPuzzle> = emptyList(),
    val groups: List<ConnectionGroup> = emptyList(),
    val duels: List<DuelPuzzle> = emptyList(),
    val lineUps: List<LineUpPuzzle> = emptyList(),
    val series: List<SeriesPuzzle> = emptyList(),
    val scores: List<ScorePuzzle> = emptyList()
)

/**
 * The puzzles of a daily game for a day and a taste, the same for everyone
 * who shares it: made the first time someone of that taste opens the game
 * that day, and kept, so the day's answers never change.
 */
data class DailyPuzzle(
    @PK(generation = NONE) val id: DailyPuzzlePk,
    @Json val data: DailyPuzzleData
) : Entity<DailyPuzzlePk>
