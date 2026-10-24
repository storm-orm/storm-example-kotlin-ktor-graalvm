package st.orm.demo.imdb.service

import io.ktor.server.config.ApplicationConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A service a visitor can sign in with: a git provider over OAuth
 * ([GitProvider]), or Freepod, the platform the application is deployed on
 * ([FreepodSignIn]). A sign-in links the service's account to the
 * visitor's account under [key] (UserLogin), whichever service it was, so
 * one account can have a login of each.
 */
interface SignInProvider {
    val key: String
    val label: String
}

/**
 * Signing in with Freepod. On Freepod the platform signs the visitor in
 * itself, at `/.freepod/auth/login`, and hands every request of a signed-in
 * visitor to the application with the account in headers, which it strips
 * from what a client sends. Anywhere else a client could set those headers,
 * so they are read only where the deployment says it runs on Freepod
 * ([enabled], `accounts.freepod.enabled` in application.conf), which is also
 * when the sign-in page offers it.
 *
 * The visitor signs in by choosing Freepod, not by being signed in at
 * Freepod: signing out here then stays signed out, while the Freepod session
 * lives on for the platform's other apps.
 */
class FreepodSignIn(val enabled: Boolean) : SignInProvider {
    override val key = "freepod"
    override val label = "Freepod"

    /** The platform's sign-in, returning to the application's [callback] path. */
    fun loginUrl(callback: String): String = "$LOGIN?rd=" + URLEncoder.encode(callback, StandardCharsets.UTF_8)

    /**
     * The account the platform signed in, from its headers: the stable
     * account id, and the display name, which arrives percent-encoded. Null
     * when the request carries no account, or when not on Freepod.
     */
    fun profile(accountId: String?, encodedName: String?): ProviderProfile? {
        if (!enabled || accountId.isNullOrBlank()) return null
        val name = encodedName?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }?.ifBlank { null }
        return ProviderProfile(subject = accountId, username = name ?: accountId, name = name, avatarUrl = null)
    }

    companion object {
        /** The headers the platform sets for a signed-in visitor. */
        const val USER_HEADER = "X-Freepod-User"
        const val NAME_HEADER = "X-Freepod-Name"
        private const val LOGIN = "/.freepod/auth/login"
    }
}

/** Freepod sign-in as the configuration has it: on only where the deployment says it runs on Freepod. */
fun ApplicationConfig.freepodSignIn(): FreepodSignIn =
    FreepodSignIn(enabled = propertyOrNull("accounts.freepod.enabled")?.getString()?.toBoolean() ?: false)
