package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

/**
 * A round of Name That Cast: the movie the house picked. A dependent
 * one-to-one: the key is the match itself. The players' bids are its
 * [CastBid]s and the names given its [CastAnswer]s.
 */
data class CastRound(
    @PK(generation = NONE) @FK val match: GameMatch,
    @FK val movie: Movie
) : Entity<GameMatch>

data class CastBidPk(
    val matchId: Long,
    val userId: String
)

/** How many of the cast a player says they can name, and when they last said so: a tie goes to the first. */
data class CastBid(
    @PK(generation = NONE) val id: CastBidPk,
    @FK @Persist(insertable = false, updatable = false) val match: Ref<GameMatch>,
    @FK @Persist(insertable = false, updatable = false) val user: User,
    val count: Int,
    val bidAt: Instant
) : Entity<CastBidPk> {
    constructor(match: GameMatch, user: User, count: Int, bidAt: Instant) : this(
        id = CastBidPk(matchId = match.id, userId = user.id),
        match = match.ref(),
        user = user,
        count = count,
        bidAt = bidAt
    )
}

data class CastAnswerPk(
    val matchId: Long,
    val position: Int
)

/**
 * A name given in a round, in its place: as the player typed it, and the
 * name in the credits it matched, or null when it matched no one.
 */
data class CastAnswer(
    @PK(generation = NONE) val id: CastAnswerPk,
    @FK @Persist(insertable = false, updatable = false) val match: Ref<GameMatch>,
    @FK val user: User,
    val answer: String,
    val credit: String?,
    val answeredAt: Instant
) : Entity<CastAnswerPk> {
    constructor(match: GameMatch, position: Int, user: User, answer: String, credit: String?, answeredAt: Instant) : this(
        id = CastAnswerPk(matchId = match.id, position = position),
        match = match.ref(),
        user = user,
        answer = answer,
        credit = credit,
        answeredAt = answeredAt
    )

    val position: Int get() = id.position

    /** Whether the name matched someone in the credits. */
    val right: Boolean get() = credit != null
}
