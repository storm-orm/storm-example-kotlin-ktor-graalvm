package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.GameCard
import st.orm.demo.imdb.model.GameCardPk
import st.orm.demo.imdb.model.GameCard_
import st.orm.demo.imdb.model.GameMatch
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface GameCardRepository : EntityRepository<GameCard, GameCardPk> {

    /** A Higher or Lower match's movies, in their order. */
    fun findCards(match: GameMatch): List<GameCard> =
        select(GameCard_.match eq match)
            .orderBy(GameCard_.id.position)
            .resultList
}
