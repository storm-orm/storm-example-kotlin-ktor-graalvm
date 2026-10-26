package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref

data class SwipeRoundCardPk(
    val matchId: Long,
    val position: Int
)

/** A movie of a swipe round, in its place: every player of the round swipes the same deck in the same order. */
data class SwipeRoundCard(
    @PK(generation = NONE) val id: SwipeRoundCardPk,
    @FK @Persist(insertable = false, updatable = false) val match: Ref<GameMatch>,
    @FK val movie: Movie
) : Entity<SwipeRoundCardPk> {
    constructor(match: GameMatch, position: Int, movie: Movie) : this(
        id = SwipeRoundCardPk(matchId = match.id, position = position),
        match = match.ref(),
        movie = movie
    )

    val position: Int get() = id.position
}
