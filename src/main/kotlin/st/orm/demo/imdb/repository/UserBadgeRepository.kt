package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserBadge
import st.orm.demo.imdb.model.UserBadgePk
import st.orm.demo.imdb.model.UserBadge_
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface UserBadgeRepository : EntityRepository<UserBadge, UserBadgePk> {

    /** The badges the user earned. */
    fun findAll(user: User): List<UserBadge> =
        findAll(UserBadge_.user eq user)
}
