package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserLogin
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserLoginRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.transaction
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Accounts without a sign-up: every visitor has one from the first request,
 * as a random id in a cookie, and the watchlist and the seen list belong to
 * it. Signing in with a git provider links the provider's account to it,
 * which is what carries the lists to another browser; the account id stays
 * what it was, so nothing is rekeyed.
 */
class AccountService(
    private val userRepository: UserRepository,
    private val userLoginRepository: UserLoginRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository
) {
    private val random = SecureRandom()

    /** A new account id: 128 random bits, URL-safe, the value of the cookie. */
    fun newAccountId(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { random.nextBytes(it) })

    /** Whether a cookie value has the shape of an account id, so a stray value never reaches the database. */
    fun isAccountId(value: String): Boolean = ACCOUNT_ID.matches(value)

    /**
     * The account behind an id: its row, or, for an account that has not
     * saved anything yet, the user as the first save would store it.
     */
    fun findUser(accountId: String): User =
        userRepository.findById(accountId) ?: newUser(accountId, Instant.now())

    /**
     * Finishes a sign-in at a provider for the account in the visitor's
     * cookie and returns the user the visitor continues as, which the caller
     * puts in the cookie. Two cases, one transaction:
     *
     * - The provider account is linked to a user already: that user is the
     *   one, with the profile refreshed. Whatever the visitor saved under the
     *   cookie account meanwhile moves to it, and the cookie account goes.
     * - It is not linked yet: it is linked to the cookie account, which gets
     *   the profile's name and avatar. The lists stay where they are.
     */
    suspend fun signIn(accountId: String, provider: GitProvider, profile: ProviderProfile): User = transaction {
        val now = Instant.now()
        val login = userLoginRepository.findLogin(provider.key, profile.subject)
        if (login != null) {
            val user = login.user.withProfile(profile, now)
            userRepository.update(user)
            if (user.id != accountId) {
                userRepository.findById(accountId)?.let { anonymous ->
                    watchlistRepository.moveTo(anonymous, user)
                    movieSeenRepository.moveTo(anonymous, user)
                    userRepository.remove(anonymous)
                }
            }
            user
        } else {
            val user = findUser(accountId).withProfile(profile, now)
            userRepository.upsert(user)
            userLoginRepository.insert(UserLogin(provider.key, profile.subject, user, profile.username, now))
            user
        }
    }

    private fun newUser(accountId: String, now: Instant) =
        User(id = accountId, name = null, avatarUrl = null, createdAt = now, lastSeenAt = now)

    private fun User.withProfile(profile: ProviderProfile, now: Instant) =
        copy(name = profile.name ?: profile.username, avatarUrl = profile.avatarUrl, lastSeenAt = now)

    companion object {
        private val ACCOUNT_ID = Regex("[A-Za-z0-9_-]{22}")
    }
}
