package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMatch_
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greater
import st.orm.template.inList
import st.orm.template.isNull
import java.time.Instant

interface GameMatchRepository : EntityRepository<GameMatch, Long> {

    /** The rounds of several clubs still open at [now], the newest first: what their members can play. */
    fun findOpen(clubs: List<Club>, now: Instant, limit: Int): List<GameMatch> =
        if (clubs.isEmpty()) emptyList() else select((GameMatch_.club inList clubs) and GameMatch_.endedAt.isNull() and (GameMatch_.closesAt greater now))
            .orderByDescending(GameMatch_.createdAt)
            .limit(limit)
            .resultList

    /** A club's matches started since [since], the newest first: the games on its page. */
    fun findRecentOf(club: Club, since: Instant, limit: Int): List<GameMatch> =
        select((GameMatch_.club eq club) and (GameMatch_.createdAt greater since))
            .orderByDescending(GameMatch_.createdAt)
            .limit(limit)
            .resultList
}
