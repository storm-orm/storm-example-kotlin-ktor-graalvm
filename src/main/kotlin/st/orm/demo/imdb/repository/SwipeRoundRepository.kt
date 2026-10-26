package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.SwipeRoundCard
import st.orm.demo.imdb.model.SwipeRoundCardPk
import st.orm.demo.imdb.model.SwipeRoundCard_
import st.orm.demo.imdb.model.SwipeRoundSwipe
import st.orm.demo.imdb.model.SwipeRoundSwipePk
import st.orm.demo.imdb.model.SwipeRoundSwipe_
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface SwipeRoundCardRepository : EntityRepository<SwipeRoundCard, SwipeRoundCardPk> {

    /** A round's deck, in its order. */
    fun findCards(match: GameMatch): List<SwipeRoundCard> =
        select(SwipeRoundCard_.match eq match)
            .orderBy(SwipeRoundCard_.id.position)
            .resultList
}

interface SwipeRoundSwipeRepository : EntityRepository<SwipeRoundSwipe, SwipeRoundSwipePk> {

    /** Every swipe of a round, by all its players. */
    fun findSwipes(match: GameMatch): List<SwipeRoundSwipe> =
        findAll(SwipeRoundSwipe_.match eq match)
}
