package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.DraftPick
import st.orm.demo.imdb.model.DraftPickPk
import st.orm.demo.imdb.model.DraftPick_
import st.orm.demo.imdb.model.GameMatch
import st.orm.repository.EntityRepository
import st.orm.template.eq
import st.orm.template.entityId
import st.orm.template.inList

interface DraftPickRepository : EntityRepository<DraftPick, DraftPickPk> {

    /** A draft's picks, in their order, each with its movie and who picked it. */
    fun findPicks(match: GameMatch): List<DraftPick> =
        select(DraftPick_.match eq match)
            .orderBy(DraftPick_.id.position)
            .resultList

    /** The picks of several drafts at once, by the match's id, each in order: the Play page's list reads them in one go. */
    fun findPicksOf(matches: List<GameMatch>): Map<Long, List<DraftPick>> =
        if (matches.isEmpty()) emptyMap() else select(DraftPick_.match inList matches).orderBy(DraftPick_.id.position).resultList.groupBy { it.match.entityId() }
}
