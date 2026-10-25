package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserWatchProvider
import st.orm.demo.imdb.model.UserWatchProviderPk
import st.orm.demo.imdb.model.UserWatchProvider_
import st.orm.demo.imdb.model.WatchProvider
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface UserWatchProviderRepository : EntityRepository<UserWatchProvider, UserWatchProviderPk> {

    /** The services the user has, with their names and logos: the join comes with the foreign key. */
    fun findAll(user: User): List<WatchProvider> =
        findAll(UserWatchProvider_.user eq user).map { it.watchProvider }

    /** Replaces the services the user has with these, as the account page saves them. */
    fun replace(user: User, providers: List<WatchProvider>) {
        removeAll(UserWatchProvider_.user eq user)
        if (providers.isNotEmpty()) insert(providers.map { UserWatchProvider(user, it) })
    }
}
