package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class RecommendationDismissalPk(
    val userId: String,
    val movieId: String
)

/**
 * A recommended movie the user said they are not interested in: the same
 * shape as the lists, keyed by the pair of user and movie. It is never
 * recommended again and weighs a little against its people, genres and
 * decade, like a mild thumbs down. The movie comes along, since the taste
 * profile reads it like a seed.
 */
data class RecommendationDismissal(
    @PK(generation = NONE) val id: RecommendationDismissalPk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    val dismissedAt: Instant
) : Entity<RecommendationDismissalPk> {
    constructor(user: User, movie: Movie, dismissedAt: Instant) : this(
        id = RecommendationDismissalPk(userId = user.id, movieId = movie.id),
        user = user.ref(),
        movie = movie,
        dismissedAt = dismissedAt
    )
}
