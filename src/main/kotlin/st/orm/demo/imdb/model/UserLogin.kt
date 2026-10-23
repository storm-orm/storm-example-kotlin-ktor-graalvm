package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.Instant

data class UserLoginPk(
    val provider: String,
    val subject: String
)

/**
 * A git provider account linked to a [User]: the provider's name (github,
 * gitlab, bitbucket, codeberg) and the subject, the id the provider keeps
 * stable for the account, which a login name is not. Signing in looks the
 * pair up to find the user; the user is loaded with the login, so a sign-in
 * is one query. The username is the provider login name at the time of
 * linking, kept for display.
 */
data class UserLogin(
    @PK(generation = NONE) val id: UserLoginPk,
    @FK val user: User,
    val username: String,
    val linkedAt: Instant
) : Entity<UserLoginPk> {
    constructor(provider: String, subject: String, user: User, username: String, linkedAt: Instant) : this(
        id = UserLoginPk(provider = provider, subject = subject),
        user = user,
        username = username,
        linkedAt = linkedAt
    )
}
