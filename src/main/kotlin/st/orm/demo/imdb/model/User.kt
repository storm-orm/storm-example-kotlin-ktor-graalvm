package st.orm.demo.imdb.model

import st.orm.DbTable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.Instant

/**
 * A visitor's account, the owner of a watchlist and a seen list. The id is
 * the random value the application put in the visitor's cookie, so an
 * account exists from the first visit without a sign-up; its row is written
 * on the first thing the visitor saves. Signing in with a git provider links
 * the provider's account to it ([UserLogin]), which fills in the name and
 * the avatar: an account with a name is one somebody signed in to.
 *
 * The table is app_user because user is a reserved word in PostgreSQL.
 */
@DbTable("app_user")
data class User(
    @PK(generation = NONE) val id: String,
    val name: String?,
    val avatarUrl: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant
) : Entity<String>
