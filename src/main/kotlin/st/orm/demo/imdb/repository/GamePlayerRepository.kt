package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.Page
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
import st.orm.template.isNotNull
import st.orm.template.isNull

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

    /** The user's games still open to them, each with its match, the newest first. */
    fun findOpen(user: User): List<GamePlayer> =
        select((GamePlayer_.user eq user) and GamePlayer_.closedAt.isNull())
            .orderByDescending(GamePlayer_.match.createdAt)
            .resultList

    /** The user's closed games, each with its match, the last closed first, a page at a time: their archive. */
    fun findClosed(user: User, page: Int, size: Int): Page<GamePlayer> =
        select((GamePlayer_.user eq user) and GamePlayer_.closedAt.isNotNull())
            .orderByDescending(GamePlayer_.closedAt)
            .page(page, size)

    /** The user's best score in a game played on their own: the streak to beat. */
    fun findBestSolo(user: User, game: Game): Int =
        select<BestScore, _, _> { "MAX(${GamePlayer_.score})" }
            .where((GamePlayer_.user eq user) and (GamePlayer_.match.game eq game) and (GamePlayer_.match.mode eq GameMode.SOLO))
            .singleResult
            .best ?: 0
}
