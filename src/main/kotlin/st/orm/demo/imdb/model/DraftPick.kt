package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class DraftPickPk(
    val matchId: Long,
    val position: Int
)

/**
 * A pick of a Movie Draft, in its place: the movie, the [player] who picked
 * it, none for the house's picks in a draft on one's own, and whether the
 * pick was made for a player who let their turn go by ([auto]).
 */
data class DraftPick(
    @PK(generation = NONE) val id: DraftPickPk,
    @FK @Persist(insertable = false, updatable = false) val match: Ref<GameMatch>,
    @FK val movie: Movie,
    @FK val player: User?,
    val auto: Boolean,
    val pickedAt: Instant
) : Entity<DraftPickPk> {
    constructor(match: GameMatch, position: Int, movie: Movie, player: User?, auto: Boolean, pickedAt: Instant) : this(
        id = DraftPickPk(matchId = match.id, position = position),
        match = match.ref(),
        movie = movie,
        player = player,
        auto = auto,
        pickedAt = pickedAt
    )

    val position: Int get() = id.position
}
