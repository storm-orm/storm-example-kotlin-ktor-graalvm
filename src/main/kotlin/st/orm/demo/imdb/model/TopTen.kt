package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class TopTenPk(
    val userId: String,
    val movieId: String
)

/**
 * A movie in a user's top ten, at its [position], 1 the best. Ten at most,
 * keyed by the pair, so a movie is in the list once; the movie comes with
 * the row, as the list is shown with its posters. A movie put in the top
 * ten counts as watched.
 */
data class TopTen(
    @PK(generation = NONE) val id: TopTenPk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    val position: Int,
    val addedAt: Instant
) : Entity<TopTenPk> {
    constructor(user: User, movie: Movie, position: Int, addedAt: Instant) : this(
        id = TopTenPk(userId = user.id, movieId = movie.id),
        user = user.ref(),
        movie = movie,
        position = position,
        addedAt = addedAt
    )
}
