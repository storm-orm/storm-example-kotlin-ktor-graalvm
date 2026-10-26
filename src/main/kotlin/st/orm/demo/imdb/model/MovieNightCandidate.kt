package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref

data class MovieNightCandidatePk(
    val nightId: Long,
    val movieId: String
)

/**
 * A movie to vote on for a movie night, picked when it was planned: its
 * place among the candidates, the best first, and why it was picked, as
 * its card says it.
 */
data class MovieNightCandidate(
    @PK(generation = NONE) val id: MovieNightCandidatePk,
    @FK @Persist(insertable = false, updatable = false) val night: Ref<MovieNight>,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    val position: Int,
    val reason: String
) : Entity<MovieNightCandidatePk> {
    constructor(night: MovieNight, movie: Movie, position: Int, reason: String) : this(
        id = MovieNightCandidatePk(nightId = night.id, movieId = movie.id),
        night = night.ref(),
        movie = movie,
        position = position,
        reason = reason
    )
}
