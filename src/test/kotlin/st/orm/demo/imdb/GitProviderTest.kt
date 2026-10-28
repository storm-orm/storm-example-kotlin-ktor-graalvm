package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.service.ConfiguredProvider
import st.orm.demo.imdb.service.GitProvider
import st.orm.demo.imdb.service.GitSignIn
import st.orm.demo.imdb.service.ProviderProfile
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The provider definitions against the shapes the four providers answer
 * with, and the URL a sign-in starts at. The exchange itself talks to the
 * provider and is not under test here.
 */
class GitProviderTest {

    private val objectMapper = jacksonObjectMapper()

    private fun GitProvider.parse(json: String) = parseProfile(objectMapper.readTree(json))

    @Test
    fun `GitHub keys on the numeric id and falls back when the name is unset`() {
        val profile = GitProvider.GITHUB.parse("""{"id": 583231, "login": "octocat", "name": null, "avatar_url": "https://avatars.githubusercontent.com/u/583231"}""")

        assertEquals(ProviderProfile("583231", "octocat", null, "https://avatars.githubusercontent.com/u/583231"), profile)
    }

    @Test
    fun `GitLab reads the username and the name`() {
        val profile = GitProvider.GITLAB.parse("""{"id": 42, "username": "jane", "name": "Jane Doe", "avatar_url": "https://gitlab.com/uploads/jane.png"}""")

        assertEquals(ProviderProfile("42", "jane", "Jane Doe", "https://gitlab.com/uploads/jane.png"), profile)
    }

    @Test
    fun `Bitbucket keys on the account id and reads the avatar link`() {
        val profile = GitProvider.BITBUCKET.parse(
            """{"account_id": "557058:abc", "nickname": "jdoe", "display_name": "J. Doe", "links": {"avatar": {"href": "https://bitbucket.org/account/jdoe/avatar/"}}}"""
        )

        assertEquals(ProviderProfile("557058:abc", "jdoe", "J. Doe", "https://bitbucket.org/account/jdoe/avatar/"), profile)
    }

    @Test
    fun `Codeberg reads the login and treats an empty full name as none`() {
        val profile = GitProvider.CODEBERG.parse("""{"id": 7, "login": "forge", "full_name": "", "avatar_url": "https://codeberg.org/avatars/7"}""")

        assertEquals("7", profile.subject)
        assertEquals("forge", profile.username)
        assertNull(profile.name)
    }

    @Test
    fun `the authorize URL carries the client, the callback, the state and the scope`() {
        val signIn = GitSignIn(listOf(ConfiguredProvider(GitProvider.GITLAB, "client-1", "secret-1")), objectMapper)

        val url = signIn.authorizeUrl(signIn.provider("gitlab")!!, "https://movies.orm.st/signin/gitlab/callback", "st4te")

        assertTrue(url.startsWith("https://gitlab.com/oauth/authorize?"))
        assertTrue(url.contains("client_id=client-1"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fmovies.orm.st%2Fsignin%2Fgitlab%2Fcallback"))
        assertTrue(url.contains("state=st4te"))
        assertTrue(url.contains("scope=read_user"))
        assertTrue(!url.contains("secret"))
        assertNull(signIn.provider("github"))
        assertEquals(listOf("gitlab"), signIn.providers.map { it.key })
    }
}
