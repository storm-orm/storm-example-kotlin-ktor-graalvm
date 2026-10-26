package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.DraftPick
import st.orm.demo.imdb.model.DraftPickPk
import st.orm.demo.imdb.model.DraftPick_
import st.orm.demo.imdb.model.GameMatch
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface DraftPickRepository : EntityRepository<DraftPick, DraftPickPk> {

    /** A draft's picks, in their order, each with its movie and who picked it. */
    fun findPicks(match: GameMatch): List<DraftPick> =
        select(DraftPick_.match eq match)
            .orderBy(DraftPick_.id.position)
            .resultList
}
