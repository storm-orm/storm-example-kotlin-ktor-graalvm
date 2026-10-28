package st.orm.demo.imdb.service

import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * What a sign-in provider tells about the account that signed in. The
 * subject is the id the provider keeps stable for the account; the rest is
 * for display.
 */
data class ProviderProfile(
    val subject: String,
    val username: String,
    val name: String?,
    val avatarUrl: String?
)

/**
 * A git provider the application can sign a visitor in with, through the
 * OAuth 2 authorization code flow all four speak: the visitor is sent to
 * [authorizeUrl], comes back with a code, the code is exchanged at
 * [tokenUrl] for an access token, and the token reads the account from
 * [profileUrl]. The providers differ only in the URLs, the scope that lets
 * the token read the account, how the token endpoint wants the client
 * credentials, and the shape of the profile.
 */
data class GitProvider(
    override val key: String,
    override val label: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val profileUrl: String,
    /** The scope that lets the access token read the account, or null when the provider needs none. */
    val scope: String?,
    /** Whether the token endpoint takes the client credentials as HTTP basic authentication rather than form fields. */
    val basicClientAuthentication: Boolean = false,
    val parseProfile: (JsonNode) -> ProviderProfile
) : SignInProvider {
    companion object {
        val GITHUB = GitProvider(
            key = "github",
            label = "GitHub",
            authorizeUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            profileUrl = "https://api.github.com/user",
            scope = null
        ) {
            ProviderProfile(
                subject = it.path("id").asString(),
                username = it.path("login").asString(),
                name = it.path("name").asString(null)?.ifBlank { null },
                avatarUrl = it.path("avatar_url").asString(null)
            )
        }

        val GITLAB = GitProvider(
            key = "gitlab",
            label = "GitLab",
            authorizeUrl = "https://gitlab.com/oauth/authorize",
            tokenUrl = "https://gitlab.com/oauth/token",
            profileUrl = "https://gitlab.com/api/v4/user",
            scope = "read_user"
        ) {
            ProviderProfile(
                subject = it.path("id").asString(),
                username = it.path("username").asString(),
                name = it.path("name").asString(null)?.ifBlank { null },
                avatarUrl = it.path("avatar_url").asString(null)
            )
        }

        val BITBUCKET = GitProvider(
            key = "bitbucket",
            label = "Bitbucket",
            authorizeUrl = "https://bitbucket.org/site/oauth2/authorize",
            tokenUrl = "https://bitbucket.org/site/oauth2/access_token",
            profileUrl = "https://api.bitbucket.org/2.0/user",
            // Bitbucket takes the scopes from the consumer's registration (account, for this URL).
            scope = null,
            basicClientAuthentication = true
        ) {
            ProviderProfile(
                subject = it.path("account_id").asString(),
                username = it.path("nickname").asString(null) ?: it.path("username").asString(),
                name = it.path("display_name").asString(null)?.ifBlank { null },
                avatarUrl = it.path("links").path("avatar").path("href").asString(null)
            )
        }

        val CODEBERG = GitProvider(
            key = "codeberg",
            label = "Codeberg",
            authorizeUrl = "https://codeberg.org/login/oauth/authorize",
            tokenUrl = "https://codeberg.org/login/oauth/access_token",
            profileUrl = "https://codeberg.org/api/v1/user",
            scope = "read:user"
        ) {
            ProviderProfile(
                subject = it.path("id").asString(),
                username = it.path("login").asString(),
                name = it.path("full_name").asString(null)?.ifBlank { null },
                avatarUrl = it.path("avatar_url").asString(null)
            )
        }

        val ALL = listOf(GITHUB, GITLAB, BITBUCKET, CODEBERG)
    }
}

/** A provider with the client id and secret of the application's registration at it. */
data class ConfiguredProvider(
    val provider: GitProvider,
    val clientId: String,
    val clientSecret: String
)

/**
 * The providers the deployment has credentials for, from application.conf
 * (accounts.<provider>.clientId and clientSecret, set through the
 * environment). A provider without credentials is left out, so the sign-in
 * page offers exactly the providers the application was registered at.
 */
fun ApplicationConfig.configuredProviders(): List<ConfiguredProvider> =
    GitProvider.ALL.mapNotNull { provider ->
        val clientId = propertyOrNull("accounts.${provider.key}.clientId")?.getString()?.ifBlank { null }
        val clientSecret = propertyOrNull("accounts.${provider.key}.clientSecret")?.getString()?.ifBlank { null }
        if (clientId == null || clientSecret == null) null else ConfiguredProvider(provider, clientId, clientSecret)
    }

/** Thrown when a provider refuses the code exchange or the profile read. */
class SignInFailedException(message: String) : RuntimeException(message)

/**
 * The provider side of a sign-in: the URL that starts it and the exchange
 * that ends it. Both HTTP calls go through the JDK client, like the
 * Wikipedia reads, and run on the IO dispatcher.
 */
class GitSignIn(
    providers: List<ConfiguredProvider>,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) {
    private val providersByKey = providers.associateBy { it.provider.key }

    /** The configured providers, for the sign-in page. */
    val providers: List<GitProvider> = providers.map { it.provider }

    fun provider(key: String): ConfiguredProvider? = providersByKey[key]

    /** Where to send the visitor to sign in at the provider, returning to [redirectUri] with [state]. */
    fun authorizeUrl(configured: ConfiguredProvider, redirectUri: String, state: String): String {
        val parameters = buildList {
            add("client_id" to configured.clientId)
            add("redirect_uri" to redirectUri)
            add("response_type" to "code")
            add("state" to state)
            configured.provider.scope?.let { add("scope" to it) }
        }
        return configured.provider.authorizeUrl + "?" + formEncode(parameters)
    }

    /**
     * Exchanges the code the provider sent the visitor back with for an
     * access token and reads the account with it.
     */
    suspend fun fetchProfile(configured: ConfiguredProvider, code: String, redirectUri: String): ProviderProfile =
        withContext(Dispatchers.IO) {
            val accessToken = exchangeCode(configured, code, redirectUri)
            val provider = configured.provider
            val request = HttpRequest.newBuilder(URI.create(provider.profileUrl))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                throw SignInFailedException("${provider.label} did not return the account (HTTP ${response.statusCode()}).")
            }
            provider.parseProfile(objectMapper.readTree(response.body())).also {
                if (it.subject.isBlank()) throw SignInFailedException("${provider.label} returned an account without an id.")
            }
        }

    private fun exchangeCode(configured: ConfiguredProvider, code: String, redirectUri: String): String {
        val provider = configured.provider
        val parameters = buildList {
            add("grant_type" to "authorization_code")
            add("code" to code)
            add("redirect_uri" to redirectUri)
            if (!provider.basicClientAuthentication) {
                add("client_id" to configured.clientId)
                add("client_secret" to configured.clientSecret)
            }
        }
        val request = HttpRequest.newBuilder(URI.create(provider.tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply {
                if (provider.basicClientAuthentication) {
                    val credentials = "${configured.clientId}:${configured.clientSecret}"
                    header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8)))
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(parameters)))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw SignInFailedException("${provider.label} refused the sign-in (HTTP ${response.statusCode()}).")
        }
        return objectMapper.readTree(response.body()).path("access_token").asString(null)
            ?: throw SignInFailedException("${provider.label} returned no access token.")
    }

    private fun formEncode(parameters: List<Pair<String, String>>): String =
        parameters.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }

    companion object {
        private const val USER_AGENT = "storm-movies (https://movies.orm.st)"
    }
}
