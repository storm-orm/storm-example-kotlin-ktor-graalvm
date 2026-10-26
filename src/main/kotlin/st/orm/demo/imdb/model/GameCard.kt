package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.math.BigDecimal

data class GameCardPk(
    val matchId: Long,
    val position: Int
)

/**
 * A movie of a Higher or Lower match, in its place: every player gets the
 * same movies in the same order. The rating is the one the movie had when
 * the match was made, so the answers hold while IMDb's ratings move.
 */
data class GameCard(
    @PK(generation = NONE) val id: GameCardPk,
    @FK @Persist(insertable = false, updatable = false) val match: Ref<GameMatch>,
    @FK val movie: Movie,
    val rating: BigDecimal
) : Entity<GameCardPk> {
    constructor(match: GameMatch, position: Int, movie: Movie, rating: BigDecimal) : this(
        id = GameCardPk(matchId = match.id, position = position),
        match = match.ref(),
        movie = movie,
        rating = rating
    )

    val position: Int get() = id.position
}
