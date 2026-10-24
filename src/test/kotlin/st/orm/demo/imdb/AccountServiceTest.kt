package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
import st.orm.demo.imdb.repository.RecommendationEventRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.repository.UserLoginRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.service.AccountService
import st.orm.demo.imdb.service.GitProvider
import st.orm.demo.imdb.service.ProviderProfile
import st.orm.demo.imdb.service.RecommendationService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

/**
 * The account behind the cookie, and what signing in with a git provider
 * does to it: a first sign-in links the provider account to the cookie
 * account, a later one from another browser switches to the account the
 * provider login is linked to and carries over what was saved meanwhile.
 */
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class AccountServiceTest {

    private val alice = ProviderProfile(subject = "1001", username = "alice", name = "Alice Example", avatarUrl = "https://avatars.example/alice.png")

    @Test
    fun `an account id is random, URL-safe, and recognised`(orm: ORMTemplate) {
        val accountService = accountService(orm)
        val first = accountService.newAccountId()
        val second = accountService.newAccountId()

        assertEquals(22, first.length)
        assertTrue(first != second)
        assertTrue(accountService.isAccountId(first))
        assertFalse(accountService.isAccountId("not an id"))
        assertFalse(accountService.isAccountId("tt0133093' OR 1=1 --"))
    }

    @Test
    fun `an account that saved nothing has no row and is anonymous`(orm: ORMTemplate) {
        val accountService = accountService(orm)
        val id = accountService.newAccountId()

        val user = accountService.findUser(id)

        assertEquals(id, user.id)
        assertNull(user.name)
        assertNull(orm.repository<UserRepository>().findById(id))
    }

    @Test
    fun `the first sign-in links the provider account to the cookie account and names it`(orm: ORMTemplate) {
        val accountService = accountService(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val anonymous = orm.insertUser("visitor-first")
        watchlistRepository.insert(Watchlist(anonymous, movieRepository.getById("tt0133093"), Instant.now()))

        val user = runBlocking { accountService.signIn(anonymous.id, GitProvider.GITHUB, alice) }

        // The account keeps its id: the cookie stays valid and nothing is rekeyed.
        assertEquals(anonymous.id, user.id)
        assertEquals("Alice Example", user.name)
        assertEquals("https://avatars.example/alice.png", user.avatarUrl)
        assertEquals("Alice Example", orm.repository<UserRepository>().getById(anonymous.id).name)
        val login = orm.repository<UserLoginRepository>().findLogin("github", "1001")
        assertNotNull(login)
        assertEquals(anonymous.id, login!!.user.id)
        assertEquals("alice", login.username)
        assertTrue(watchlistRepository.contains(user, movieRepository.getById("tt0133093")))
    }

    @Test
    fun `a sign-in from a new browser switches to the linked account and brings the new browser's lists`(orm: ORMTemplate) {
        val accountService = accountService(orm)
        val movieRepository = orm.repository<MovieRepository>()
        val watchlistRepository = orm.repository<WatchlistRepository>()
        val movieSeenRepository = orm.repository<MovieSeenRepository>()
        val userRepository = orm.repository<UserRepository>()
        val matrix = movieRepository.getById("tt0133093")
        val godfather = movieRepository.getById("tt0068646")
        val pulpFiction = movieRepository.getById("tt0110912")

        // The first browser signed in and saved The Matrix.
        val original = orm.insertUser("visitor-laptop")
        runBlocking { accountService.signIn(original.id, GitProvider.GITHUB, alice) }
        watchlistRepository.insert(Watchlist(original, matrix, Instant.now()))

        // A second browser saved The Godfather, marked Pulp Fiction seen and viewed The Matrix before signing in.
        val phone = orm.insertUser("visitor-phone")
        watchlistRepository.insert(Watchlist(phone, godfather, Instant.now()))
        movieSeenRepository.insert(MovieSeen(phone, pulpFiction, Instant.now()))
        val movieViewRepository = orm.repository<MovieViewRepository>()
        movieViewRepository.insert(MovieView(matrix, Instant.now(), phone))

        val user = runBlocking { accountService.signIn(phone.id, GitProvider.GITHUB, alice.copy(name = "Alice E.")) }

        assertEquals(original.id, user.id)
        assertEquals("Alice E.", user.name)
        assertTrue(watchlistRepository.contains(user, matrix))
        assertTrue(watchlistRepository.contains(user, godfather))
        assertTrue(movieSeenRepository.contains(user, pulpFiction))
        // The phone's views are the account's recently viewed now.
        assertEquals(listOf("tt0133093"), movieViewRepository.findRecentViews(user, 10).map { it.movie.id() })
        // The phone's anonymous account is gone, lists included.
        assertNull(userRepository.findById(phone.id))
        assertEquals(0, watchlistRepository.findPage(phone, 0, 10).totalCount())
    }

    @Test
    fun `a sign-in from a browser without a row needs no merge`(orm: ORMTemplate) {
        val accountService = accountService(orm)
        val original = orm.insertUser("visitor-laptop")
        runBlocking { accountService.signIn(original.id, GitProvider.GITLAB, alice) }

        val user = runBlocking { accountService.signIn(accountService.newAccountId(), GitProvider.GITLAB, alice) }

        assertEquals(original.id, user.id)
        assertEquals(1, orm.repository<UserRepository>().count())
    }

    @Test
    fun `a second provider links to the same account`(orm: ORMTemplate) {
        val accountService = accountService(orm)
        val original = orm.insertUser("visitor-laptop")
        runBlocking { accountService.signIn(original.id, GitProvider.GITHUB, alice) }

        val user = runBlocking {
            accountService.signIn(original.id, GitProvider.CODEBERG, ProviderProfile("77", "alice-cb", null, null))
        }

        assertEquals(original.id, user.id)
        // A profile without a display name falls back to the login name.
        assertEquals("alice-cb", user.name)
        val logins = orm.repository<UserLoginRepository>()
        assertEquals(original.id, logins.findLogin("github", "1001")?.user?.id)
        assertEquals(original.id, logins.findLogin("codeberg", "77")?.user?.id)
    }

    private fun accountService(orm: ORMTemplate) = AccountService(
        orm.repository<UserRepository>(),
        orm.repository<UserLoginRepository>(),
        orm.repository<WatchlistRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<RecommendationDismissalRepository>(),
        orm.repository<MovieViewRepository>(),
        RecommendationService(
            orm.repository<UserRepository>(),
            orm.repository<MovieRepository>(),
            orm.repository<MovieSeenRepository>(),
            orm.repository<WatchlistRepository>(),
            orm.repository<RecommendationDismissalRepository>(),
            orm.repository<MovieAdditionRepository>(),
            orm.repository<MovieGenreRepository>(),
            orm.repository<PrincipalRepository>(),
            orm.repository<RatingRepository>(),
            orm.repository<MovieViewRepository>(),
            orm.repository<RecommendationRepository>(),
            orm.repository<RecommendationEventRepository>()
        )
    )
}
