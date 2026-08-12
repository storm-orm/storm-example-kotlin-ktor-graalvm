package st.orm.demo.imdb

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.ktor.server.thymeleaf.Thymeleaf
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.thymeleaf.context.IExpressionContext
import org.thymeleaf.linkbuilder.StandardLinkBuilder
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import io.ktor.http.HttpStatusCode
import st.orm.NoResultException
import st.orm.demo.imdb.service.ImdbDataImporter
import st.orm.demo.imdb.web.configureRouting
import st.orm.ktor.Storm
import st.orm.serialization.StormSerializers

/**
 * The application module: plugin setup, service wiring (Dependencies.kt),
 * routing (web/Routing.kt), and the streaming import on first startup.
 *
 * Referenced from application.conf (ktor.application.modules) and started by
 * Ktor's EngineMain.
 */
fun Application.module() {
    configureObservability()
    configureStorm()
    configureDependencies()
    configureSerialization()
    configureTemplating()
    configureErrorHandling()
    configureRouting()

    // Import the dataset on first startup (skipped once movie data is present),
    // after Flyway and the Storm plugin are ready. Blocking startup until it
    // finishes is intentional — the Ktor counterpart of Spring's ApplicationRunner.
    val imdbDataImporter: ImdbDataImporter by dependencies
    imdbDataImporter.import()
}


/**
 * Storm reports every query (storm.query) and every transaction (storm.transaction)
 * as a Micrometer Observation once an ObservationRegistry is available in the
 * dependency container; the registry is wired in configureDependencies(). The
 * Prometheus registry backing it is exposed for scraping at /metrics: look for
 * storm_query_seconds, tagged with the operation, execution kind, entity type and
 * statement shape, and storm_transaction_seconds.
 */
private fun Application.configureObservability() {
    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    dependencies {
        provide<PrometheusMeterRegistry> { prometheusRegistry }
    }
    install(MicrometerMetrics) {
        registry = prometheusRegistry
    }
    routing {
        get("/metrics") {
            call.respondText(prometheusRegistry.scrape())
        }
    }
}

/**
 * Storm exceptions map to HTTP status codes centrally: a lookup of a row that
 * does not exist (getById on an unknown id) is a 404, not a 500. See the
 * Error Handling section of the Ktor integration docs for the full recipe,
 * including constraint-violation and optimistic-lock mappings.
 */
private fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<NoResultException> { call, _ ->
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        }
    }
}

/**
 * The plugin creates the connection pool from application.conf
 * (storm.datasource) and validates every entity against the live database
 * schema at startup, failing fast on any mismatch (the default; the production
 * counterpart of the validateSchema() test). The migration hook runs Flyway
 * first, so validation always sees the migrated schema.
 */
private fun Application.configureStorm() {
    install(Storm) {
        migration {
            Flyway.configure()
                .dataSource(it)
                // Explicit resources instead of classpath scanning, which GraalVM
                // native images do not support; see FlywayResources.
                .resourceProvider(FlywayResources(listOf("db/migration/V1__create_schema.sql")))
                .load()
                .migrate()
        }
    }
}

/**
 * StormSerializers handles Ref fields; every response shape survives the
 * kotlinx.serialization round-trip unchanged.
 */
private fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json { serializersModule = StormSerializers })
    }
}

private fun Application.configureTemplating() {
    install(Thymeleaf) {
        setTemplateResolver(
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                characterEncoding = "UTF-8"
            }
        )
        // Ktor renders Thymeleaf with a non-web context; resolve context-relative
        // @{/...} links against the application root (empty context path).
        setLinkBuilder(RootContextLinkBuilder())
    }
}

/**
 * Link builder that resolves context-relative URLs (@{/...}) without a servlet
 * context. Ktor serves from the application root, so the context path is empty
 * and such links pass through unchanged. The default StandardLinkBuilder throws
 * for /-relative links unless the Thymeleaf context is a web context, which
 * Ktor's plain context is not.
 */
private class RootContextLinkBuilder : StandardLinkBuilder() {
    override fun computeContextPath(
        context: IExpressionContext?,
        base: String?,
        parameters: MutableMap<String, Any>?
    ): String = ""
}
