package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserLogin
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubInviteRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.FriendRequestRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
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
    private val movieSeenRepository: MovieSeenRepository,
    private val dismissalRepository: RecommendationDismissalRepository,
    private val movieViewRepository: MovieViewRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val clubInviteRepository: ClubInviteRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val checkInRepository: CheckInRepository,
    private val recommendationService: RecommendationService
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
     * Finishes a sign-in at a provider, a git provider or Freepod, for the
     * account in the visitor's cookie and returns the user the visitor
     * continues as, which the caller puts in the cookie. A visitor signed in
     * with one provider who signs in with another links the two. Two cases,
     * one transaction:
     *
     * - The provider account is linked to a user already: that user is the
     *   one, with the profile refreshed. Whatever the visitor saved and
     *   viewed under the cookie account moves to it, and so do the cookie
     *   account's logins, friends and clubs, if the visitor was signed in; then the cookie
     *   account goes, and every login leads to the one account.
     * - It is not linked yet: it is linked to the cookie account, which gets
     *   the profile's name, and its avatar if it has one. The lists stay
     *   where they are; signed in already, the account has one login more.
     *
     * Lists that moved change what to recommend, so the user's
     * recommendations are recomputed in the background afterwards.
     */
    suspend fun signIn(accountId: String, provider: SignInProvider, profile: ProviderProfile): User {
        val user = transaction {
            val now = Instant.now()
            val login = userLoginRepository.findLogin(provider.key, profile.subject)
            if (login != null) {
                val previous = if (login.user.id != accountId) userRepository.findById(accountId) else null
                val user = login.user.withProfile(profile, now, previous?.avatarUrl)
                userRepository.update(user)
                previous?.let {
                    watchlistRepository.moveTo(it, user)
                    movieSeenRepository.moveTo(it, user)
                    checkInRepository.moveTo(it, user)
                    dismissalRepository.moveTo(it, user)
                    movieViewRepository.moveTo(it, user)
                    userLoginRepository.moveTo(it, user)
                    friendshipRepository.moveTo(it, user)
                    clubMemberRepository.moveTo(it, user)
                    clubInviteRepository.moveTo(it, user)
                    friendRequestRepository.moveTo(it, user)
                    userRepository.remove(it)
                }
                user
            } else {
                val user = findUser(accountId).withProfile(profile, now)
                userRepository.upsert(user)
                userLoginRepository.insert(UserLogin(provider.key, profile.subject, user, profile.username, now))
                user
            }
        }
        recommendationService.refreshLater(user)
        return user
    }

    private fun newUser(accountId: String, now: Instant) =
        User(id = accountId, name = null, avatarUrl = null, createdAt = now, lastSeenAt = now)

    /**
     * The account as the provider just used describes it. A provider without
     * avatars, Freepod, leaves the one the account had, or else the one of
     * the account it joins.
     */
    private fun User.withProfile(profile: ProviderProfile, now: Instant, joinedAvatarUrl: String? = null) =
        copy(name = profile.name ?: profile.username, avatarUrl = profile.avatarUrl ?: avatarUrl ?: joinedAvatarUrl, lastSeenAt = now)

    /** Sets the country the user is from, or leaves it to the browser for null or a code that names no country. */
    suspend fun updateCountry(user: User, country: String?) = transaction {
        userRepository.update(user.copy(country = Countries.of(country)?.code))
    }

    /** The logins linked to the user, for the account page; none for an account nobody signed in to. */
    fun loginsOf(user: User): List<UserLogin> = userLoginRepository.findAll(user)

    companion object {
        private val ACCOUNT_ID = Regex("[A-Za-z0-9_-]{22}")
    }
}
