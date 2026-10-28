package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class MovieNightVotePk(
    val nightId: Long,
    val userId: String,
    val movieId: String
)

/**
 * A member's vote for one of a movie night's candidates: a member votes for
 * as many as they like, once each, so the key is the night, the member and
 * the movie. The member comes with the row, as the candidates show who
 * voted for them.
 */
data class MovieNightVote(
    @PK(generation = NONE) val id: MovieNightVotePk,
    @FK @Persist(insertable = false, updatable = false) val night: Ref<MovieNight>,
    @FK @Persist(insertable = false, updatable = false) val user: User,
    @FK @Persist(insertable = false, updatable = false) val movie: Ref<Movie>,
    val votedAt: Instant
) : Entity<MovieNightVotePk> {
    constructor(night: MovieNight, user: User, movie: Movie, votedAt: Instant) : this(
        id = MovieNightVotePk(nightId = night.id, userId = user.id, movieId = movie.id),
        night = night.ref(),
        user = user,
        movie = movie.ref(),
        votedAt = votedAt
    )
}
