package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.PK
import java.time.Instant

/** The games played as matches. */
enum class Game { HIGHER_LOWER, CHAIN }

/** Who a match is played with: on one's own, with a friend, or with a club. */
enum class GameMode { SOLO, FRIEND, CLUB }

/**
 * A match of one of the games, on one's own, with a friend or with a club:
 * its players are its [GamePlayer]s. Nobody waits on anybody: a round that
 * everyone plays in their own time closes at [closesAt], whoever played,
 * and a turn not taken in time passes to the next player; both are worked
 * out when the match is looked at, as no job runs in the background. A
 * match is over at [endedAt], or once [closesAt] has passed.
 */
data class GameMatch(
    @PK val id: Long = 0,
    val game: Game,
    val mode: GameMode,
    @FK val club: Club?,
    @FK val host: User,
    val createdAt: Instant,
    val closesAt: Instant?,
    val endedAt: Instant?
) : Entity<Long> {
    /** Whether the match is over at [now]: ended, or closed. */
    fun isOver(now: Instant): Boolean = endedAt != null || (closesAt != null && !now.isBefore(closesAt))
}
