package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.QuizDay
import st.orm.repository.EntityRepository
import java.time.LocalDate

interface QuizDayRepository : EntityRepository<QuizDay, LocalDate> {

    /** The ids of the movies of every day's quiz so far, so a movie is the answer once. */
    fun findAnswered(): Set<String> =
        findAll().map { it.movie.id }.toSet()
}
