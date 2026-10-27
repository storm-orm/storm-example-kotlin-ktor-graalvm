package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.ChainLink
import st.orm.demo.imdb.model.ChainLinkPk
import st.orm.demo.imdb.model.ChainLink_
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.eq
import st.orm.template.entityId
import st.orm.template.inList

interface ChainLinkRepository : EntityRepository<ChainLink, ChainLinkPk> {

    /** A chain's movies, in their order, each with who added it and through whom. */
    /** Every link the user added, in any chain: their badges count them. */
    fun findByPlayer(user: User): List<ChainLink> =
        findAll(ChainLink_.player eq user)

    fun findLinks(match: GameMatch): List<ChainLink> =
        select(ChainLink_.match eq match)
            .orderBy(ChainLink_.id.position)
            .resultList

    /** The links of several chains at once, by the match's id, each in order: the Play page's list reads them in one go. */
    fun findLinksOf(matches: List<GameMatch>): Map<Long, List<ChainLink>> =
        if (matches.isEmpty()) emptyMap() else select(ChainLink_.match inList matches).orderBy(ChainLink_.id.position).resultList.groupBy { it.match.entityId() }
}
