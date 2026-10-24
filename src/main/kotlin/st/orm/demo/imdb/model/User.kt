package st.orm.demo.imdb.model

import st.orm.DbTable
import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.demo.imdb.serialization.InstantAsStringSerializer
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
 * Serializable like [Movie], so a view's Ref to its user travels with it
 * (see [MovieView]).
 */
@Serializable
@DbTable("app_user")
data class User(
    @PK(generation = NONE) val id: String,
    val name: String?,
    val avatarUrl: String?,
    @Serializable(with = InstantAsStringSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantAsStringSerializer::class) val lastSeenAt: Instant,
    /** Whether friends see what this user watches at all. */
    val shareSeen: Boolean = true,
    /** Whether friends see it as this user's, rather than as "a friend". */
    val shareName: Boolean = true,
    /** Whether friends see the days this user watched on. */
    val shareDates: Boolean = true,
    /** What club members see, in every club this user has not set apart ([ClubMember]): what they watch, */
    val clubShareSeen: Boolean = true,
    /** as this user's, */
    val clubShareName: Boolean = true,
    /** and the days, off by default, as members of a club are not always friends. */
    val clubShareDates: Boolean = false,
    /** The country the user is from (ISO 3166-1 alpha-2), for where movies play; null leaves it to the browser. */
    val country: String? = null
) : Entity<String>
