package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.MovieViewDay
import st.orm.demo.imdb.model.MovieViewDayPk
import st.orm.demo.imdb.model.MovieViewDay_
import st.orm.repository.EntityRepository
import st.orm.template.greaterEq
import java.time.LocalDate

interface MovieViewDayRepository : EntityRepository<MovieViewDay, MovieViewDayPk> {

    /** The folded days since a date, oldest first: the history the view log no longer holds. */
    fun findSince(date: LocalDate): List<MovieViewDay> =
        select(MovieViewDay_.viewDate greaterEq date)
            .orderBy(MovieViewDay_.viewDate, MovieViewDay_.movie)
            .resultList
}
