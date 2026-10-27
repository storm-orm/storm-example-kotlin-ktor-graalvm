package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.CastAnswer
import st.orm.demo.imdb.model.CastAnswerPk
import st.orm.demo.imdb.model.CastAnswer_
import st.orm.demo.imdb.model.CastBid
import st.orm.demo.imdb.model.CastBidPk
import st.orm.demo.imdb.model.CastBid_
import st.orm.demo.imdb.model.CastRound
import st.orm.demo.imdb.model.GameMatch
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface CastRoundRepository : EntityRepository<CastRound, GameMatch>

interface CastBidRepository : EntityRepository<CastBid, CastBidPk> {

    /** A round's bids, the highest first, of equal ones the first made. */
    fun findBids(match: GameMatch): List<CastBid> =
        select(CastBid_.match eq match)
            .orderByDescending(CastBid_.count)
            .orderBy(CastBid_.bidAt)
            .resultList
}

interface CastAnswerRepository : EntityRepository<CastAnswer, CastAnswerPk> {

    /** The names given in a round, in their order. */
    fun findAnswers(match: GameMatch): List<CastAnswer> =
        select(CastAnswer_.match eq match)
            .orderBy(CastAnswer_.id.position)
            .resultList
}
