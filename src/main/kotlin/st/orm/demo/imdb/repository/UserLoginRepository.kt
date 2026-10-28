package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserLogin
import st.orm.demo.imdb.model.UserLoginPk
import st.orm.demo.imdb.model.UserLogin_
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface UserLoginRepository : EntityRepository<UserLogin, UserLoginPk> {

    /**
     * The login a provider account is linked to, or null when it is not
     * linked yet. The user comes with the login: a sign-in is this one query.
     */
    fun findLogin(provider: String, subject: String): UserLogin? =
        findById(UserLoginPk(provider = provider, subject = subject))

    /** The logins linked to a user, the first linked first, for the account page. */
    fun findAll(user: User): List<UserLogin> =
        select(UserLogin_.user eq user)
            .orderBy(UserLogin_.linkedAt)
            .resultList

    /**
     * Moves one user's logins to another, for the sign-in that joins the
     * account the visitor was signed in to with the one the new login leads
     * to: both logins lead to the same account afterwards. A login is keyed
     * by the provider account, so the move updates the user column.
     */
    fun moveTo(from: User, into: User) {
        update(findAll(from).map { it.copy(user = into) })
    }
}
