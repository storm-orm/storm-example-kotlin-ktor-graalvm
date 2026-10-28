package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.YearShare
import st.orm.demo.imdb.model.YearShare_
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq

interface YearShareRepository : EntityRepository<YearShare, String> {

    /** The link the user shared their year with, if they did. */
    fun findShare(user: User, year: Int): YearShare? =
        find((YearShare_.user eq user) and (YearShare_.year eq year))
}
