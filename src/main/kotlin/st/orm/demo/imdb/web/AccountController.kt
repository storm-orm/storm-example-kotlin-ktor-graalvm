package st.orm.demo.imdb.web

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import st.orm.demo.imdb.service.AccountService
import st.orm.demo.imdb.service.GitSignIn
import st.orm.demo.imdb.service.SignInFailedException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in with a git provider, in the OAuth 2 authorization code flow:
 * the sign-in page lists the configured providers, choosing one sends the
 * visitor to it, and the provider sends the visitor back to the callback
 * with a code. The state that ties the two requests together, and the page
 * to return to, travel in a short-lived cookie.
 */
fun Route.accountRoutes(accountService: AccountService, gitSignIn: GitSignIn) {
    val random = SecureRandom()

    get("/signin") {
        call.respondPage(
            ThymeleafContent(
                "signin",
                mapOf(
                    "providers" to gitSignIn.providers,
                    "returnTo" to returnPath(call.request.queryParameters["rd"]),
                    "failure" to call.request.queryParameters["failure"]
                )
            )
        )
    }

    get("/signin/{provider}") {
        val configured = gitSignIn.provider(call.parameters.getOrFail("provider"))
            ?: return@get call.respondText("Unknown sign-in provider", status = HttpStatusCode.NotFound)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        val returnTo = returnPath(call.request.queryParameters["rd"])
        call.response.cookies.append(
            Cookie(
                name = SIGN_IN_COOKIE,
                value = "$state:$returnTo",
                maxAge = SIGN_IN_COOKIE_SECONDS,
                path = "/signin",
                secure = call.isSecure(),
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax")
            )
        )
        call.respondRedirect(gitSignIn.authorizeUrl(configured, call.callbackUri(configured.provider.key), state))
    }

    get("/signin/{provider}/callback") {
        val configured = gitSignIn.provider(call.parameters.getOrFail("provider"))
            ?: return@get call.respondText("Unknown sign-in provider", status = HttpStatusCode.NotFound)
        val (state, returnTo) = call.request.cookies[SIGN_IN_COOKIE]?.split(":", limit = 2)?.takeIf { it.size == 2 }
            ?: return@get call.respondRedirect("/signin?failure=expired")
        call.response.cookies.append(call.expiredCookie(SIGN_IN_COOKIE, path = "/signin"))
        if (call.request.queryParameters["state"] != state) {
            return@get call.respondRedirect("/signin?failure=expired")
        }
        val code = call.request.queryParameters["code"]
            ?: return@get call.respondRedirect("/signin?failure=denied&rd=" + returnTo.urlEncoded())
        val user = try {
            val profile = gitSignIn.fetchProfile(configured, code, call.callbackUri(configured.provider.key))
            accountService.signIn(call.accountId(), configured.provider, profile)
        } catch (e: SignInFailedException) {
            call.application.environment.log.warn("Sign-in with ${configured.provider.label} failed: ${e.message}")
            return@get call.respondRedirect("/signin?failure=refused&rd=" + returnTo.urlEncoded())
        }
        call.setAccountCookie(user.id)
        call.respondRedirect(returnTo)
    }

    // Signing out forgets the account cookie; the next page starts a new,
    // anonymous account. The signed-in account and its lists stay, behind
    // the provider login.
    post("/signout") {
        call.clearAccountCookie()
        call.respondRedirect("/")
    }
}

private const val SIGN_IN_COOKIE = "signin"
private const val SIGN_IN_COOKIE_SECONDS = 10 * 60

/** Where the provider sends the visitor back to: this application, as the visitor reaches it. */
private fun ApplicationCall.callbackUri(provider: String) = "${publicOrigin()}/signin/$provider/callback"

/** A page of this site to return to after signing in; anything else, and an absent value, means the home page. */
private fun returnPath(requested: String?): String =
    requested?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.contains('\\') } ?: "/"

private fun String.urlEncoded() = URLEncoder.encode(this, StandardCharsets.UTF_8)
