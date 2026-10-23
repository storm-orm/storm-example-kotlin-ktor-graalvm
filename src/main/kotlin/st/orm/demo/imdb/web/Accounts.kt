package st.orm.demo.imdb.web

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.service.AccountService

/**
 * The visitor's account on a request. The account is a random id in the
 * account cookie: a visitor without one gets one on the first page, and the
 * cookie is renewed on every page, so it lasts a year past the last visit.
 * Signing in (AccountController) replaces the id with that of the account
 * the provider login is linked to.
 */
private val AccountServiceKey = AttributeKey<AccountService>("AccountService")
private val AccountIdKey = AttributeKey<String>("AccountId")
private val CurrentUserKey = AttributeKey<User>("CurrentUser")

private const val ACCOUNT_COOKIE = "account"
private const val ONE_YEAR_SECONDS = 365 * 24 * 60 * 60

fun Application.configureAccounts() {
    val accountService: AccountService by dependencies
    attributes.put(AccountServiceKey, accountService)
}

/**
 * Renders a page with the visitor's account in its model, under `account`,
 * for the navbar. Every page responds through here, so no controller
 * passes the account itself.
 */
suspend fun ApplicationCall.respondPage(content: ThymeleafContent) {
    respond(ThymeleafContent(content.template, content.model + ("account" to currentUser())))
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

/** The visitor's account as a user, looked up once per request. */
fun ApplicationCall.currentUser(): User {
    attributes.getOrNull(CurrentUserKey)?.let { return it }
    val user = application.attributes[AccountServiceKey].findUser(accountId())
    attributes.put(CurrentUserKey, user)
    return user
}

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
