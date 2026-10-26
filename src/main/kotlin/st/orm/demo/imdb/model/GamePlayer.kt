package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import java.time.Instant

data class GamePlayerPk(
    val matchId: Long,
    val userId: String
)

/**
 * A player of a match, keyed by the pair: their place in the turns, where
 * a game takes turns, their score, and whether they played their part to
 * the end. The match and the user come with the row: the lists of one's
 * games show the match, and the boards name the players.
 */
data class GamePlayer(
    @PK(generation = NONE) val id: GamePlayerPk,
    @FK @Persist(insertable = false, updatable = false) val match: GameMatch,
    @FK @Persist(insertable = false, updatable = false) val user: User,
    val turnOrder: Int,
    val score: Int,
    val finished: Boolean,
    val updatedAt: Instant
) : Entity<GamePlayerPk> {
    constructor(match: GameMatch, user: User, turnOrder: Int, now: Instant) : this(
        id = GamePlayerPk(matchId = match.id, userId = user.id),
        match = match,
        user = user,
        turnOrder = turnOrder,
        score = 0,
        finished = false,
        updatedAt = now
    )
}
