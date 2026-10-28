package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class WatchlistPk(
    val userId: String,
    val movieId: String
)

/**
 * A movie saved to a user's watchlist. The primary key is the pair of user
 * and movie, which makes saving the same movie twice a key violation —
 * exactly the semantics a watchlist toggle needs. The user is a Ref: the
 * list is always read for one user, so the rows never need the user row.
 */
data class Watchlist(
    @PK(generation = NONE) val id: WatchlistPk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    val addedAt: Instant
) : Entity<WatchlistPk> {
    constructor(user: User, movie: Movie, addedAt: Instant) : this(
        id = WatchlistPk(userId = user.id, movieId = movie.id),
        user = user.ref(),
        movie = movie,
        addedAt = addedAt
    )
}
