package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.Club_
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface ClubRepository : EntityRepository<Club, Long> {

    /**
     * The club, locked until the transaction ends: the changes to its
     * admins and members take it first, so two admins stepping down at once
     * cannot leave it without one.
     */
    fun lock(clubId: Long): Club? =
        select(Club_.id eq clubId)
            .forUpdate()
            .resultList
            .singleOrNull()
}
