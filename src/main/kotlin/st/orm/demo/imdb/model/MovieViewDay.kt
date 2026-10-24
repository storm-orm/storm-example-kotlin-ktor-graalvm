package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.entityId
import java.time.LocalDate

data class MovieViewDayPk(
    val movieId: String,
    val viewDate: LocalDate
)

/**
 * The views of a movie on one day, folded from the view log once the day is
 * older than a month (ViewLogService): the history of what was viewed,
 * without the row per visit. The movie and the date are the key, as they
 * are the identity of the count; both are exposed as read-only fields
 * mapped to the key's columns, so queries can name them. The column is
 * view_date, since day is a reserved word on H2.
 */
data class MovieViewDay(
    @PK(generation = NONE) val id: MovieViewDayPk,
    @FK @Persist(insertable = false, updatable = false) val movie: Ref<Movie>,
    @Persist(insertable = false, updatable = false) val viewDate: LocalDate,
    val viewCount: Int
) : Entity<MovieViewDayPk> {
    constructor(movie: Ref<Movie>, viewDate: LocalDate, viewCount: Int) : this(
        id = MovieViewDayPk(movieId = movie.entityId(), viewDate = viewDate),
        movie = movie,
        viewDate = viewDate,
        viewCount = viewCount
    )
}
