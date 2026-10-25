package st.orm.demo.imdb.web

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.origin
import io.ktor.server.request.acceptLanguageItems
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.service.AccountService
import st.orm.demo.imdb.service.Countries
import st.orm.demo.imdb.service.Country
import st.orm.demo.imdb.service.FreepodSignIn
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.StreamingService
import st.orm.demo.imdb.service.TmdbService
import java.util.Locale

/**
 * The visitor's account on a request. The account is a random id in the
 * account cookie: a visitor without one gets one on the first page, and the
 * cookie is renewed on every page, so it lasts a year past the last visit.
 * Signing in (AccountController) replaces the id with that of the account
 * the provider login is linked to.
 */
private val AccountServiceKey = AttributeKey<AccountService>("AccountService")
private val FreepodSignInKey = AttributeKey<FreepodSignIn>("FreepodSignIn")
private val FriendServiceKey = AttributeKey<FriendService>("FriendService")
private val TmdbServiceKey = AttributeKey<TmdbService>("TmdbService")
private val StreamingServiceKey = AttributeKey<StreamingService>("StreamingService")
private val AccountIdKey = AttributeKey<String>("AccountId")
private val CurrentUserKey = AttributeKey<User>("CurrentUser")

private const val ACCOUNT_COOKIE = "account"

/** Set once the visitor has been to the catch-up page, so the home page stops pointing them there. */
const val CATCH_UP_COOKIE = "catch-up"
private const val ONE_YEAR_SECONDS = 365 * 24 * 60 * 60

fun Application.configureAccounts() {
    val accountService: AccountService by dependencies
    val freepodSignIn: FreepodSignIn by dependencies
    val friendService: FriendService by dependencies
    val tmdbService: TmdbService by dependencies
    val streamingService: StreamingService by dependencies
    attributes.put(AccountServiceKey, accountService)
    attributes.put(FreepodSignInKey, freepodSignIn)
    attributes.put(FriendServiceKey, friendService)
    attributes.put(TmdbServiceKey, tmdbService)
    attributes.put(StreamingServiceKey, streamingService)
}

/**
 * Renders a page with what the layout shows on every page in its model: the
 * visitor's account under `account`, for the navbar, under `requestCount`
 * the friend requests a signed-in visitor has to answer, for the badge on
 * the Community tab, under `hostedOnFreepod` whether the application runs
 * on Freepod with its sign-in on, under `tmdb` whether TMDB is on, for
 * the Trailer buttons and the footer's credit, and under `hasServices`
 * whether a signed-in visitor has streaming services, for their logos on
 * the posters. Every page responds through here, so no controller passes
 * them itself.
 */
suspend fun ApplicationCall.respondPage(content: ThymeleafContent) {
    val user = currentUser()
    val requestCount = if (user.name != null) application.attributes[FriendServiceKey].countRequests(user) else 0L
    val hasServices = isSignedIn() && country() != null && application.attributes[StreamingServiceKey].servicesOf(user).isNotEmpty()
    val layout = mapOf(
        "account" to user,
        "requestCount" to requestCount,
        "hostedOnFreepod" to application.attributes[FreepodSignInKey].enabled,
        "tmdb" to application.attributes[TmdbServiceKey].enabled,
        "hasServices" to hasServices
    )
    respond(ThymeleafContent(content.template, content.model + layout))
}

/**
 * The id of the visitor's account: the cookie's, or a fresh one that is set
 * on this response. The row for it is written on the first save.
 */
fun ApplicationCall.accountId(): String {
    attributes.getOrNull(AccountIdKey)?.let { return it }
    val accountService = application.attributes[AccountServiceKey]
    val id = request.cookies[ACCOUNT_COOKIE]?.takeIf { accountService.isAccountId(it) } ?: accountService.newAccountId()
    setAccountCookie(id)
    attributes.put(AccountIdKey, id)
    return id
}

/**
 * Whether the browser came back with its account cookie: a returning
 * visitor, unlike a first page or a crawler, which keeps no cookies.
 */
fun ApplicationCall.isReturningVisitor(): Boolean =
    request.cookies[ACCOUNT_COOKIE]?.let { application.attributes[AccountServiceKey].isAccountId(it) } == true

/** The visitor's account as a user, looked up once per request. */
fun ApplicationCall.currentUser(): User {
    attributes.getOrNull(CurrentUserKey)?.let { return it }
    val user = application.attributes[AccountServiceKey].findUser(accountId())
    attributes.put(CurrentUserKey, user)
    return user
}

/**
 * The visitor's country, for where movies play: the one the account names,
 * else the first the browser's languages name (nl-NL is the Netherlands),
 * else none, as for a browser that names a language only.
 */
fun ApplicationCall.country(): Country? =
    Countries.of(currentUser().country) ?: browserCountry()

/** The first country the browser's languages name, by preference. */
fun ApplicationCall.browserCountry(): Country? =
    request.acceptLanguageItems().firstNotNullOfOrNull { Countries.of(Locale.forLanguageTag(it.value).country.ifEmpty { null }) }

/**
 * Whether the visitor signed in: an account with a name. Only such a visitor's
 * requests ask TMDB, so a crawler, which never signs in, cannot run up calls.
 */
fun ApplicationCall.isSignedIn(): Boolean = currentUser().name != null

/** Puts the account in the cookie, for a year; the cookie never reaches scripts. */
fun ApplicationCall.setAccountCookie(accountId: String) {
    response.cookies.append(
        Cookie(
            name = ACCOUNT_COOKIE,
            value = accountId,
            encoding = CookieEncoding.RAW,
            maxAge = ONE_YEAR_SECONDS,
            path = "/",
            secure = isSecure(),
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax")
        )
    )
}

/** Remembers something about this browser for a year, in a cookie that never reaches scripts. */
fun ApplicationCall.rememberCookie(name: String, value: String) {
    response.cookies.append(
        Cookie(
            name = name,
            value = value,
            encoding = CookieEncoding.RAW,
            maxAge = ONE_YEAR_SECONDS,
            path = "/",
            secure = isSecure(),
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax")
        )
    )
}

fun ApplicationCall.clearAccountCookie() {
    response.cookies.append(expiredCookie(ACCOUNT_COOKIE, path = "/"))
}

/** A cookie that expires the one with this name and path, with the same flags the live one has. */
fun ApplicationCall.expiredCookie(name: String, path: String) = Cookie(
    name = name,
    value = "",
    encoding = CookieEncoding.RAW,
    maxAge = 0,
    expires = GMTDate.START,
    path = path,
    secure = isSecure(),
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax")
)

/** Whether the visitor reached the application over HTTPS, directly or through the platform's proxy. */
fun ApplicationCall.isSecure(): Boolean =
    request.headers["X-Forwarded-Proto"]?.equals("https", ignoreCase = true) == true || request.origin.scheme == "https"

/**
 * The origin the visitor sees, for the URL a provider sends the visitor
 * back to: the proxy's forwarded host when there is one, else the request's.
 */
fun ApplicationCall.publicOrigin(): String {
    val scheme = if (isSecure()) "https" else "http"
    val host = request.headers["X-Forwarded-Host"]
        ?: request.headers["Host"]
        ?: "${request.origin.serverHost}:${request.origin.serverPort}"
    return "$scheme://$host"
}
