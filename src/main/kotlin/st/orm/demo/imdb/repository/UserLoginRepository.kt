package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.UserLogin
import st.orm.demo.imdb.model.UserLoginPk
import st.orm.repository.EntityRepository

interface UserLoginRepository : EntityRepository<UserLogin, UserLoginPk> {

    /**
     * The login a provider account is linked to, or null when it is not
     * linked yet. The user comes with the login: a sign-in is this one query.
     */
    fun findLogin(provider: String, subject: String): UserLogin? =
        findById(UserLoginPk(provider = provider, subject = subject))
}
