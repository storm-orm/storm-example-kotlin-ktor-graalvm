package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.QuizPlay
import st.orm.demo.imdb.model.QuizPlayPk
import st.orm.demo.imdb.model.QuizPlay_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList
import st.orm.template.isTrue
import java.time.LocalDate

interface QuizPlayRepository : EntityRepository<QuizPlay, QuizPlayPk> {

    /** The games of a day played to the end by several users, each with its player: a club's board. */
    fun findFinished(users: List<User>, day: LocalDate): List<QuizPlay> =
        if (users.isEmpty()) emptyList() else findAll((QuizPlay_.user inList users) and (QuizPlay_.id.quizOn eq day) and QuizPlay_.finished.isTrue())

    /** The days the user guessed the movie, the latest first: their streak. */
    fun findSolvedDays(user: User): List<LocalDate> =
        select((QuizPlay_.user eq user) and QuizPlay_.solved.isTrue())
            .orderByDescending(QuizPlay_.id.quizOn)
            .resultList
            .map { it.day }
}
