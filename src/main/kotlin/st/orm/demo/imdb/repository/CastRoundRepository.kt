package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.CastAnswer
import st.orm.demo.imdb.model.CastAnswerPk
import st.orm.demo.imdb.model.CastAnswer_
import st.orm.demo.imdb.model.CastBid
import st.orm.demo.imdb.model.CastBidPk
import st.orm.demo.imdb.model.CastBid_
import st.orm.demo.imdb.model.CastRound
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.eq
import st.orm.template.entityId
import st.orm.template.inList

interface CastRoundRepository : EntityRepository<CastRound, GameMatch>

interface CastBidRepository : EntityRepository<CastBid, CastBidPk> {

    /** Every bid the user made, in any round. */
    fun findByUser(user: User): List<CastBid> =
        findAll(CastBid_.user eq user)

    /** A round's bids, the highest first, of equal ones the first made. */
    /** The bids of several rounds at once, by the match's id, each highest first: the Play page's list reads them in one go. */
    fun findBidsOf(matches: List<GameMatch>): Map<Long, List<CastBid>> =
        if (matches.isEmpty()) emptyMap() else select(CastBid_.match inList matches).orderByDescending(CastBid_.count).orderBy(CastBid_.bidAt).resultList.groupBy { it.match.entityId() }

    fun findBids(match: GameMatch): List<CastBid> =
        select(CastBid_.match eq match)
            .orderByDescending(CastBid_.count)
            .orderBy(CastBid_.bidAt)
            .resultList
}

interface CastAnswerRepository : EntityRepository<CastAnswer, CastAnswerPk> {

    /** Every name the user gave, in any round. */
    fun findByUser(user: User): List<CastAnswer> =
        findAll(CastAnswer_.user eq user)

    /** The names given in a round, in their order. */
    fun findAnswers(match: GameMatch): List<CastAnswer> =
        select(CastAnswer_.match eq match)
            .orderBy(CastAnswer_.id.position)
            .resultList

    /** The names given in several rounds at once, by the match's id, each in order. */
    fun findAnswersOf(matches: List<GameMatch>): Map<Long, List<CastAnswer>> =
        if (matches.isEmpty()) emptyMap() else select(CastAnswer_.match inList matches).orderBy(CastAnswer_.id.position).resultList.groupBy { it.match.entityId() }
}
