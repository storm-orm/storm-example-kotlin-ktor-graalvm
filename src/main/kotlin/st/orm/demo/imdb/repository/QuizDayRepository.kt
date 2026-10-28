package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.QuizDay
import st.orm.demo.imdb.model.QuizDayPk
import st.orm.repository.EntityRepository

interface QuizDayRepository : EntityRepository<QuizDay, QuizDayPk> {

    /** The ids of the movies of every day's quiz so far, so a movie is the answer once. */
    fun findAnswered(): Set<String> =
        findAll().map { it.movie.id }.toSet()
}
