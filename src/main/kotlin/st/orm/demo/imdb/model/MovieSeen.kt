package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class MovieSeenPk(
    val userId: String,
    val movieId: String
)

/**
 * A movie a user has marked as seen: the same shape as [Watchlist], keyed by
 * the pair of user and movie, so marking a movie seen twice is a key
 * violation and the toggle has its semantics. Marking a movie seen takes it
 * off the watchlist (MovieSeenService). A thumbs up or down ([liked]) is
 * optional and sharpens the recommendations; most seen movies have none.
 *
 * [seenAt] is when the movie was marked seen, or last checked in. Its
 * watches, each on a day, are its [CheckIn]s; a seen movie without any is
 * history, seen at some time before, whose day is not known or shown.
 */
data class MovieSeen(
    @PK(generation = NONE) val id: MovieSeenPk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    val seenAt: Instant,
    val liked: Boolean? = null
) : Entity<MovieSeenPk> {
    constructor(user: User, movie: Movie, seenAt: Instant, liked: Boolean? = null) : this(
        id = MovieSeenPk(userId = user.id, movieId = movie.id),
        user = user.ref(),
        movie = movie,
        seenAt = seenAt,
        liked = liked
    )
}
