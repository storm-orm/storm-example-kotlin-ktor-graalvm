package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import java.time.Instant

interface UserRepository : EntityRepository<User, String> {

    /**
     * Records that the user is active now and returns the user as stored. An
     * account that has not saved anything yet has no row, so this is an
     * upsert: the first save creates the row, every later one updates the
     * moment. Called from the writes only; a page view costs no write.
     */
    fun touch(user: User, at: Instant): User =
        user.copy(lastSeenAt = at).also { upsert(it) }
}
