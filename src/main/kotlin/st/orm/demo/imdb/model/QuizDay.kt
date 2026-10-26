package st.orm.demo.imdb.model

import st.orm.DbColumn
import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.LocalDate

/**
 * The movie of a day's quiz, the same for everyone: picked the first time
 * someone opens the quiz that day, and kept, so the day's answer never
 * changes as the catalogue does. The column is `quiz_on`, as `day` is a
 * keyword to some databases.
 */
data class QuizDay(
    @PK(generation = NONE) @DbColumn("quiz_on") val day: LocalDate,
    @FK val movie: Movie
) : Entity<LocalDate>
