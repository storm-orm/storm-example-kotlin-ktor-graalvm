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
 * Kevin Bacon, the centre of Six Degrees of Kevin Bacon, the game in which
 * every actor is linked to him through shared movies in six steps or fewer:
 * a link through him earns a bonus in Movie Chain and Connect.
 */
const val KEVIN_BACON = "nm0000102"

data class ChainLinkPk(
    val matchId: Long,
    val position: Int
)

/**
 * A movie of a Movie Chain, in its place: the first one the match started
 * with, each next one sharing an actor with the one before, [via] that
 * actor. The [player] who added it, or none for the first and for the
 * house's moves in a game on one's own, and the points it scored.
 */
data class ChainLink(
    @PK(generation = NONE) val id: ChainLinkPk,
    @FK @Persist(insertable = false, updatable = false) val match: Ref<GameMatch>,
    @FK val movie: Movie,
    @FK val player: User?,
    @FK val via: Person?,
    val points: Int,
    val addedAt: Instant
) : Entity<ChainLinkPk> {
    constructor(match: GameMatch, position: Int, movie: Movie, player: User?, via: Person?, points: Int, addedAt: Instant) : this(
        id = ChainLinkPk(matchId = match.id, position = position),
        match = match.ref(),
        movie = movie,
        player = player,
        via = via,
        points = points,
        addedAt = addedAt
    )

    val position: Int get() = id.position

    /** Whether the link goes through Kevin Bacon, which the games reward. */
    val throughBacon: Boolean get() = via?.id == KEVIN_BACON

    /** Whether the link goes through Meryl Streep, which Movie Chain rewards too. */
    val throughStreep: Boolean get() = via?.id == MERYL_STREEP
}
