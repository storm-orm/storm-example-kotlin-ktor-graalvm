package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.PK
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

/**
 * A watch of a movie on a day: a check-in. A movie watched again is checked
 * in again, so a user has as many check-ins of a movie as watches, each its
 * own row; the movie being seen at all is its [MovieSeen]. The movie comes
 * with the check-in, which the check-ins page and the friends feed show; the
 * user is a Ref, as check-ins are read for a user already known.
 */
data class CheckIn(
    @PK val id: Long = 0,
    @FK val user: Ref<User>,
    @FK val movie: Movie,
    val watchedAt: Instant
) : Entity<Long> {
    constructor(user: User, movie: Movie, watchedAt: Instant) : this(user = user.ref(), movie = movie, watchedAt = watchedAt)
}
