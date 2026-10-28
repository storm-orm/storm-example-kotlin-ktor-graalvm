package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.LocalDate

data class QuizDayPk(
    val quizOn: LocalDate,
    /** The players' taste, their movie filter as its key ([MovieFilter.key]); empty for every movie. */
    val filterKey: String
)

/**
 * The movie of a day's quiz for a taste, the same for everyone who shares
 * it: picked the first time someone of that taste opens the quiz that day,
 * and kept, so the day's answer never changes as the catalogue does.
 */
data class QuizDay(
    @PK(generation = NONE) val id: QuizDayPk,
    @FK val movie: Movie
) : Entity<QuizDayPk>
