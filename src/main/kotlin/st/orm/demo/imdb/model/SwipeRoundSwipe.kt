package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class SwipeRoundSwipePk(
    val matchId: Long,
    val userId: String,
    val movieId: String
)

/**
 * A player's swipe in a round, kept with the round: a round's matches and
 * its ballot are made of these, whatever the player swiped on the same
 * movie elsewhere before or since.
 */
data class SwipeRoundSwipe(
    @PK(generation = NONE) val id: SwipeRoundSwipePk,
    @FK @Persist(insertable = false, updatable = false) val match: Ref<GameMatch>,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val movie: Ref<Movie>,
    val way: SwipeWay,
    val swipedAt: Instant,
    /** How long the poster was looked at before it went, in milliseconds, as the page measured it; null for a swipe without. */
    val decidedMs: Int? = null
) : Entity<SwipeRoundSwipePk> {
    constructor(match: GameMatch, user: User, movie: Movie, way: SwipeWay, swipedAt: Instant, decidedMs: Int? = null) : this(
        id = SwipeRoundSwipePk(matchId = match.id, userId = user.id, movieId = movie.id),
        match = match.ref(),
        user = user.ref(),
        movie = movie.ref(),
        way = way,
        swipedAt = swipedAt,
        decidedMs = decidedMs
    )
}
