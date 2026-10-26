package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.GamePlayerPk
import st.orm.demo.imdb.model.GamePlayer_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greater
import java.time.Instant

/**
 * Query result shape: the best score of a set of games, none while there
 * is none. Not backed by a database table or view, so it is a plain data
 * class — deliberately not a Data type.
 */
@GenerateMetamodel
data class BestScore(val best: Int?)

interface GamePlayerRepository : EntityRepository<GamePlayer, GamePlayerPk> {

    /** A match's players, in the order of their turns. */
    fun findPlayers(match: GameMatch): List<GamePlayer> =
        select(GamePlayer_.match eq match)
            .orderBy(GamePlayer_.turnOrder)
            .resultList

    /** The user's matches started since [since], each with its match, the newest first: their games. */
    fun findRecent(user: User, since: Instant, limit: Int): List<GamePlayer> =
        select((GamePlayer_.user eq user) and (GamePlayer_.match.createdAt greater since))
            .orderByDescending(GamePlayer_.match.createdAt)
            .limit(limit)
            .resultList

    /** The user's best score in a game played on their own: the streak to beat. */
    fun findBestSolo(user: User, game: Game): Int =
        select<BestScore, _, _> { "MAX(${GamePlayer_.score})" }
            .where((GamePlayer_.user eq user) and (GamePlayer_.match.game eq game) and (GamePlayer_.match.mode eq GameMode.SOLO))
            .singleResult
            .best ?: 0
}
