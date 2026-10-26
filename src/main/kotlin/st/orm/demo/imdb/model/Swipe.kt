package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

/** Which way a poster went: right for a movie to see, left for one to pass on, up for one seen. */
enum class SwipeWay { WANT, PASS, SEEN }

data class SwipePk(
    val userId: String,
    val movieId: String
)

/**
 * A user's swipe on a movie, the latest way it went, keyed by the pair: the
 * deck never deals a movie swiped before, and the swipes say whose taste
 * agrees with whose ([TasteTwin]). The movie is a Ref: the swipes are
 * compared by movie, never shown.
 */
data class Swipe(
    @PK(generation = NONE) val id: SwipePk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val movie: Ref<Movie>,
    val way: SwipeWay,
    val swipedAt: Instant
) : Entity<SwipePk> {
    constructor(user: User, movie: Movie, way: SwipeWay, swipedAt: Instant) : this(
        id = SwipePk(userId = user.id, movieId = movie.id),
        user = user.ref(),
        movie = movie.ref(),
        way = way,
        swipedAt = swipedAt
    )
}
