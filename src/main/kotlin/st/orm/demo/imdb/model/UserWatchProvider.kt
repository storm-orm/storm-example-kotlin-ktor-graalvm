package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref

data class UserWatchProviderPk(
    val userId: String,
    val watchProviderId: Int
)

/**
 * A streaming service a user has, ticked on the account page: For You shows
 * its logo on the posters of the picks it streams, and can keep to them. The
 * key is the pair, so a service is had once; the service comes with the row,
 * for its name and logo.
 */
data class UserWatchProvider(
    @PK(generation = NONE) val id: UserWatchProviderPk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val watchProvider: WatchProvider
) : Entity<UserWatchProviderPk> {
    constructor(user: User, watchProvider: WatchProvider) : this(
        id = UserWatchProviderPk(userId = user.id, watchProviderId = watchProvider.id),
        user = user.ref(),
        watchProvider = watchProvider
    )
}
