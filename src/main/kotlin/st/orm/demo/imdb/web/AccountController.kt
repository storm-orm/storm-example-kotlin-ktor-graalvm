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
import io.ktor.server.request.receiveParameters
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.Tag
import st.orm.demo.imdb.service.AccountService
import st.orm.demo.imdb.service.BadgeService
import st.orm.demo.imdb.service.Countries
import st.orm.demo.imdb.service.MovieFilterService
import st.orm.demo.imdb.service.StreamingService
import st.orm.demo.imdb.service.FreepodSignIn
import st.orm.demo.imdb.service.GitProvider
import st.orm.demo.imdb.service.GitSignIn
import st.orm.demo.imdb.service.SignInFailedException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/** A login of the signed-in account, as the account page lists it. */
data class AccountLogin(val provider: String, val label: String, val username: String)

/** A streaming service of the visitor's country, as the account page offers it, ticked or not. */
data class ServiceChoice(val id: Int, val name: String, val logoUrl: String?, val chosen: Boolean)

/** A kind of cinema as the account page offers it: a tag, or [OTHER_MOVIES] for the movies without one, ticked or not. */
data class TagChoice(val key: String, val label: String, val chosen: Boolean)

/** A language as the account page offers it, with its movies, ticked or not. */
data class LanguageChoice(val code: String, val name: String, val movies: Long, val chosen: Boolean)

/** The key of the choice for the movies without a tag: everything else. */
private const val OTHER_MOVIES = "OTHER"

/** The most languages a filter keeps to; the page offers fewer. */
private const val MAX_LANGUAGES = 50

/**
 * Signing in with a git provider, in the OAuth 2 authorization code flow:
 * the sign-in page lists the configured providers, choosing one sends the
 * visitor to it, and the provider sends the visitor back to the callback
 * with a code. The state that ties the two requests together, and the page
 * to return to, travel in a short-lived cookie. On Freepod, signing in with
 * Freepod is a round trip through the platform's own sign-in instead.
 *
 * Signed in, the same page is the account page: it lists the account's
 * logins and offers the providers not linked yet, and signing in with one
 * of them links it (AccountService.signIn). It also holds the country the
 * user is from, for where movies play, the streaming services they have,
 * and the movies they want picked for them ([MovieFilter]).
 */
fun Route.accountRoutes(
    accountService: AccountService,
    streamingService: StreamingService,
    movieFilterService: MovieFilterService,
    gitSignIn: GitSignIn,
    freepodSignIn: FreepodSignIn,
    badgeService: BadgeService
) {
    val random = SecureRandom()
    val labels = (GitProvider.ALL + freepodSignIn).associate { it.key to it.label }

    get("/signin") {
        val returnTo = returnPath(call.request.queryParameters["rd"])
        val user = call.currentUser()
        val logins = accountService.loginsOf(user)
            .map { AccountLogin(it.id.provider, labels[it.id.provider] ?: it.id.provider, it.username) }
        // Signed in, the services of the visitor's country to tick, and the ones ticked.
        val country = call.country().takeIf { user.name != null }
        val services = country?.let { streamingService.servicesIn(it) }.orEmpty()
        val myServices = if (services.isEmpty()) emptySet() else streamingService.servicesOf(user).map { it.id }.toSet()
        // Signed in, the kinds of cinema and the languages to keep the picks to.
        val filter = user.movieFilter ?: MovieFilter.NONE
        val tags = Tag.entries.map { TagChoice(it.name, it.label, it !in filter.hiddenTags) } +
            TagChoice(OTHER_MOVIES, "Everything else", filter.showsUntagged)
        val languages = if (user.name == null) emptyList() else movieFilterService.languages(filter.languages)
            .map { LanguageChoice(it.code, it.name, it.movies, it.code in filter.languages) }
        call.respondPage(
            ThymeleafContent(
                "signin",
                mapOf(
                    "providers" to gitSignIn.providers.filter { provider -> logins.none { it.provider == provider.key } },
                    "freepodLoginUrl" to freepodSignIn.loginUrl("/signin/freepod?rd=" + returnTo.urlEncoded())
                        .takeIf { freepodSignIn.enabled && logins.none { it.provider == freepodSignIn.key } },
                    "logins" to logins,
                    "returnTo" to returnTo,
                    "badgeCount" to badgeService.countOf(call.currentUser()),
                    "failure" to call.request.queryParameters["failure"],
                    "countries" to Countries.all,
                    "browserCountry" to call.browserCountry(),
                    "country" to country,
                    "services" to services.map { ServiceChoice(it.id, it.name, it.logoPath?.let(::tmdbLogoUrl), it.id in myServices) },
                    "tags" to tags,
                    "languages" to languages,
                    // Signed in, the sign-ins and their linking sit behind the Manage button, as the community's do.
                    "manage" to (call.request.queryParameters["manage"] != null)
                )
            )
        )
    }

    // The platform signed the visitor in and sent them back here, with the account in its headers.
    // A constant segment, so it takes precedence over the git providers' route below.
    get("/signin/freepod") {
        if (!freepodSignIn.enabled) return@get call.respondText("Unknown sign-in provider", status = HttpStatusCode.NotFound)
        val returnTo = returnPath(call.request.queryParameters["rd"])
        val profile = freepodSignIn.profile(
            call.request.headers[FreepodSignIn.USER_HEADER],
            call.request.headers[FreepodSignIn.NAME_HEADER]
        ) ?: return@get call.respondRedirect("/signin?failure=refused&rd=" + returnTo.urlEncoded())
        val user = accountService.signIn(call.accountId(), freepodSignIn, profile)
        call.setAccountCookie(user.id)
        call.respondRedirect(returnTo)
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

    // The country a signed-in user is from, for where movies play; empty leaves it to the browser.
    post("/account/country") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/signin")
        accountService.updateCountry(user, call.receiveParameters()["country"]?.ifBlank { null })
        call.respondRedirect("/signin")
    }

    // The streaming services a signed-in user has, among the ones of their country.
    post("/account/services") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/signin")
        val country = call.country() ?: return@post call.respondRedirect("/signin")
        val ids = call.receiveParameters().getAll("service").orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        streamingService.updateServices(user, country, ids)
        call.respondRedirect("/signin#services-card")
    }

    // The movies a signed-in user wants picked for them: the kinds of cinema ticked, and the
    // languages ticked, none meaning every language. Nothing ticked at all lets everything through.
    post("/account/movies") {
        val user = call.currentUser().takeIf { it.name != null } ?: return@post call.respondRedirect("/signin")
        val parameters = call.receiveParameters()
        val shown = parameters.getAll("show").orEmpty().toSet().ifEmpty { Tag.entries.map { it.name }.toSet() + OTHER_MOVIES }
        val filter = MovieFilter(
            hiddenTags = Tag.entries.filterNot { it.name in shown }.toSet(),
            showsUntagged = OTHER_MOVIES in shown,
            languages = parameters.getAll("language").orEmpty().filter { LANGUAGE_CODE.matches(it) }.take(MAX_LANGUAGES).toSet()
        )
        movieFilterService.updateFilter(user, filter)
        call.respondRedirect("/signin#movies-card")
    }

    // Signing out forgets the account cookie; the next page starts a new,
    // anonymous account. The signed-in account and its lists stay, behind
    // the provider login.
    post("/signout") {
        call.clearAccountCookie()
        call.respondRedirect("/")
    }
}

/** An ISO 639 code, two or three letters. */
private val LANGUAGE_CODE = Regex("[a-z]{2,3}")

private const val SIGN_IN_COOKIE = "signin"
private const val SIGN_IN_COOKIE_SECONDS = 10 * 60

/** Where the provider sends the visitor back to: this application, as the visitor reaches it. */
private fun ApplicationCall.callbackUri(provider: String) = "${publicOrigin()}/signin/$provider/callback"

/** A page of this site to return to after signing in; anything else, and an absent value, means the home page. */
private fun returnPath(requested: String?): String =
    requested?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.contains('\\') } ?: "/"

private fun String.urlEncoded() = URLEncoder.encode(this, StandardCharsets.UTF_8)
