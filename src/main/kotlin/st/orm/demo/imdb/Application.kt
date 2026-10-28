package st.orm.demo.imdb

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.di.dependencies
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.content.CachingOptions
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.accept
import io.ktor.server.request.path
import io.ktor.server.thymeleaf.Thymeleaf
import io.ktor.server.thymeleaf.ThymeleafContent
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.thymeleaf.context.IExpressionContext
import org.thymeleaf.linkbuilder.StandardLinkBuilder
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import st.orm.InvalidCursorException
import st.orm.NoResultException
import st.orm.demo.imdb.service.GalleryRefreshProperties
import st.orm.demo.imdb.service.TmdbService
import st.orm.demo.imdb.service.ImdbDataSync
import st.orm.demo.imdb.service.ImdbSyncProperties
import st.orm.demo.imdb.service.StatisticsService
import st.orm.demo.imdb.service.PersonGalleryService
import st.orm.demo.imdb.service.RecommendationService
import st.orm.demo.imdb.service.ViewLogProperties
import st.orm.demo.imdb.service.ViewLogService
import st.orm.demo.imdb.service.WikidataService
import st.orm.demo.imdb.web.configureAccounts
import st.orm.demo.imdb.web.configureRouting
import st.orm.demo.imdb.web.respondPage
import st.orm.ktor.Storm
import st.orm.serialization.StormSerializers
import st.orm.template.TransactionDispatchers
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The application module: plugin setup, service wiring (Dependencies.kt),
 * routing (web/Routing.kt), and the IMDB sync: the import on first startup, then daily.
 *
 * Referenced from application.conf (ktor.application.modules) and started by
 * Ktor's EngineMain.
 */
fun Application.module() {
    configureObservability()
    configureStorm()
    configureDependencies()
    configureSerialization()
    configureAccounts()
    configureTemplating()
    configureErrorHandling()
    configureRouting()

    configureImdbSync()

    configureStatisticsWarmUp()

    configureGalleryRefresh()
    configureTmdbExpiry()
    configureWikidata()

    configureRecommendationRefresh()

    configureViewLogFolding()
}

/**
 * The view log is folded nightly: the views older than the kept period
 * become counts per movie and day (ViewLogService), at a fixed time in UTC
 * after the IMDB sync. The timer runs in the application's own scope, which
 * cancels it at shutdown; a failed run is logged and the next night's run
 * folds what it left.
 */
private fun Application.configureViewLogFolding() {
    val properties: ViewLogProperties by dependencies
    val viewLogService: ViewLogService by dependencies
    launch {
        while (isActive) {
            delay(timeUntil(properties.foldAt).toMillis())
            try {
                viewLogService.foldOldViews()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                environment.log.error("Folding the view log failed.", e)
            }
        }
    }
}

/**
 * A visitor's recommendations are recomputed in the background whenever
 * their lists change: the writes queue the visitor, and this worker, one
 * for the application, computes them (RecommendationService). It runs in
 * the application's own scope, which cancels it at shutdown.
 */
private fun Application.configureRecommendationRefresh() {
    val recommendationService: RecommendationService by dependencies
    launch { recommendationService.processRefreshRequests() }
}

/**
 * IMDB republishes its dataset daily, so the catalogue is synced with it at startup and then once
 * a day, at a fixed time (see ImdbDataSync). On an empty database the first sync is the initial
 * import, and it runs before the application serves: blocking startup until it finishes is
 * intentional, the Ktor counterpart of Spring's ApplicationRunner. With data present, the startup
 * sync runs in the background instead, so a deploy catches up on missed days without delaying
 * startup; a catalogue synced within the last two days is left alone, since the sync would load
 * the database for a minute while the application serves, for nothing the daily run will not do. The background runs happen in the application's own
 * scope, which cancels them at shutdown. A failed run is logged; the next one catches up, since a
 * sync always brings the whole catalogue up to date.
 */
/** A catalogue with an addition this recent is in step with IMDB: no sync at startup. */
private val STARTUP_SYNC_UNLESS_FRESHER_THAN: java.time.Duration = java.time.Duration.ofHours(48)

private fun Application.configureImdbSync() {
    val properties: ImdbSyncProperties by dependencies
    if (!properties.enabled) {
        environment.log.info("IMDB sync disabled.")
        return
    }
    val imdbDataSync: ImdbDataSync by dependencies
    val statisticsService: StatisticsService by dependencies
    suspend fun syncInBackground() {
        try {
            imdbDataSync.sync()
            statisticsService.refreshCatalogueStatistics()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            environment.log.error("IMDB sync failed.", e)
        }
    }
    val imported = imdbDataSync.isEmpty()
    if (imported) {
        runBlocking { imdbDataSync.sync() }
    }
    val fresh = !imported && imdbDataSync.isFresh(STARTUP_SYNC_UNLESS_FRESHER_THAN)
    if (fresh) environment.log.info("IMDB sync: the catalogue is fresh, the startup sync is skipped; the daily sync is at {} UTC.", properties.dailyAt)
    launch {
        if (!imported && !fresh) syncInBackground()
        while (isActive) {
            delay(timeUntil(properties.dailyAt).toMillis())
            syncInBackground()
        }
    }
}

/** The time from now until [time] of day in UTC, today or else tomorrow. */
private fun timeUntil(time: LocalTime): Duration {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val today = now.with(time)
    return Duration.between(now, if (today.isAfter(now)) today else today.plusDays(1))
}

/**
 * The statistics page aggregates every movie, rating and credit, which takes a
 * moment, and the home page counts the movies per genre; the cache holding both
 * lives in memory, so it starts empty after each restart. Filling it in the
 * background as soon as the data is in place spares the first visitor that
 * wait. A failure is logged, and the page computes what it needs itself.
 */
private fun Application.configureStatisticsWarmUp() {
    val statisticsService: StatisticsService by dependencies
    launch {
        runCatching {
            statisticsService.genreCounts()
            statisticsService.buildStatisticsView()
        }.onFailure { environment.log.warn("Could not prepare the statistics in advance.", it) }
    }
}

/**
 * The photo galleries go stale where a Wikipedia article gains or loses images, so
 * a background loop re-fetches the oldest of them. It reads the stale galleries in
 * keyset windows: each window is one statement that has closed before the loop sees
 * it, so the connection is free while the loop calls Wikimedia, and each window is
 * written back in one upsert. The timer is the Ktor counterpart of a scheduled
 * method, and the application's own scope cancels it at shutdown.
 */
private fun Application.configureGalleryRefresh() {
    val properties: GalleryRefreshProperties by dependencies
    val personGalleryService: PersonGalleryService by dependencies
    launch {
        delay(properties.initialDelay.toMillis())
        while (isActive) {
            personGalleryService.refreshStaleGalleries()
            delay(properties.interval.toMillis())
        }
    }
}

/**
 * TMDB's terms let what it returns be kept six months at most: once a day, the
 * stored TMDB lookups older than that are removed (TmdbService.removeExpired).
 */
private fun Application.configureTmdbExpiry() {
    val tmdbService: TmdbService by dependencies
    launch {
        while (isActive) {
            runCatching { tmdbService.removeExpired() }
                .onFailure { environment.log.warn("Could not remove the expired TMDB lookups.", it) }
            delay(Duration.ofDays(1).toMillis())
        }
    }
}

/**
 * Wikidata tells where each movie was made and in what language, which
 * become its languages and tags (WikidataService): it is asked about the
 * movies it was not asked about lately at startup, which on the first start
 * is the whole catalogue, then once a day for the movies the IMDB sync
 * added. A failed run is logged; the next one asks what it left.
 */
private fun Application.configureWikidata() {
    if (environment.config.propertyOrNull("wikidata.enabled")?.getString()?.toBoolean() == false) {
        environment.log.info("Wikidata disabled.")
        return
    }
    val wikidataService: WikidataService by dependencies
    launch {
        while (isActive) {
            try {
                wikidataService.askAboutMovies()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                environment.log.warn("Could not ask Wikidata about the movies.", e)
            }
            delay(Duration.ofDays(1).toMillis())
        }
    }
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
        get("/healthz") {
            call.respondText("ok")
        }
    }
}

/**
 * Storm exceptions map to HTTP status codes centrally: a lookup of a row that
 * does not exist (getById on an unknown id) is a 404, not a 500, and a scroll
 * cursor the engine refuses is a 400. See the Error Handling section of the
 * Ktor integration docs for the full recipe, including constraint-violation
 * and optimistic-lock mappings. A page that is not there, asked for by a
 * browser, gets a page of its own; a script's request gets the plain status.
 */
private fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<NoResultException> { call, _ ->
            if (call.wantsPage()) call.respondNotFoundPage() else call.respondText("Not found", status = HttpStatusCode.NotFound)
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            if (call.wantsPage()) call.respondNotFoundPage()
        }
        // A cursor is opaque and is issued for one ordering: a malformed cursor, one from an
        // earlier format, or one issued for another ordering cannot resume the scroll. The
        // client asked with a parameter it cannot fix by retrying, so the answer is a 400 and
        // the list starts over without it.
        exception<InvalidCursorException> { call, _ ->
            call.respondText("Invalid cursor", status = HttpStatusCode.BadRequest)
        }
    }
}

/** Whether the request is a browser's for a page, not a script's for data. */
private fun ApplicationCall.wantsPage(): Boolean =
    !request.path().startsWith("/api/") && request.accept()?.contains("text/html") == true

private suspend fun ApplicationCall.respondNotFoundPage() {
    response.status(HttpStatusCode.NotFound)
    respondPage(ThymeleafContent("not-found", emptyMap()))
}

/**
 * The plugin creates the connection pool from application.conf
 * (storm.datasource) and validates every entity against the live database
 * schema at startup, failing fast on any mismatch (the default; the production
 * counterpart of the validateSchema() test). The migration hook runs Flyway
 * first, so validation always sees the migrated schema.
 */
private fun Application.configureStorm() {
    // As many transactions at a time as the pool has connections; the others wait suspended,
    // without a thread. On the shared IO dispatcher a burst of requests blocked every thread
    // in the pool's wait for a connection, and the transactions that held one could not get a
    // thread back to commit, so their connections stayed idle in transaction until a wait timed out.
    TransactionDispatchers.Default = Dispatchers.IO.limitedParallelism(
        environment.config.property("storm.datasource.maximumPoolSize").getString().toInt()
    )
    install(Storm) {
        migration {
            Flyway.configure()
                .dataSource(it)
                // Explicit resources instead of classpath scanning, which GraalVM
                // native images do not support; see FlywayResources.
                .resourceProvider(FlywayResources(listOf(
                    "db/migration/V1__create_schema.sql",
                    "db/migration/V2__store_folded_titles.sql",
                    "db/migration/V3__record_movie_additions.sql",
                    "db/migration/V4__personal_accounts.sql",
                    "db/migration/V5__recommendations.sql",
                    "db/migration/V6__recommendation_fatigue.sql",
                    "db/migration/V7__fold_old_views.sql",
                    "db/migration/V8__movie_view_day_natural_key.sql",
                    "db/migration/V9__personal_recently_viewed.sql",
                    "db/migration/V10__friends.sql",
                    "db/migration/V11__public_friend_invites.sql",
                    "db/migration/V12__seen_history.sql",
                    "db/migration/V13__check_ins.sql",
                    "db/migration/V14__clubs.sql",
                    "db/migration/V15__friend_requests.sql",
                    "db/migration/V16__movie_sources.sql",
                    "db/migration/V17__watch_providers.sql",
                    "db/migration/V18__refetch_watch_providers.sql",
                    "db/migration/V19__user_watch_providers.sql",
                    "db/migration/V20__movie_tags.sql",
                    "db/migration/V21__languages_from_tmdb.sql",
                    "db/migration/V22__ask_languages_again.sql",
                    "db/migration/V23__invite_accepted_count.sql",
                    "db/migration/V24__movie_countries.sql",
                    "db/migration/V25__ask_countries.sql",
                    "db/migration/V26__year_shares.sql",
                    "db/migration/V27__movie_lists.sql",
                    "db/migration/V28__movie_nights.sql",
                    "db/migration/V29__quiz.sql",
                    "db/migration/V30__games.sql",
                    "db/migration/V31__quiz_per_taste.sql",
                    "db/migration/V32__movie_night_votes.sql",
                    "db/migration/V33__movie_draft.sql",
                    "db/migration/V34__game_hints.sql",
                    "db/migration/V35__swipes.sql",
                    "db/migration/V36__connect.sql",
                    "db/migration/V37__daily_puzzles.sql",
                    "db/migration/V38__people_puzzles.sql",
                    "db/migration/V39__closed_games.sql",
                    "db/migration/V40__name_that_cast.sql",
                    "db/migration/V41__levels.sql",
                    "db/migration/V42__match_pools.sql",
                    "db/migration/V43__night_swipe.sql",
                    "db/migration/V44__badges.sql",
                    "db/migration/V45__closed_game_status.sql",
                    "db/migration/V46__reel_call.sql",
                    "db/migration/V47__goat.sql",
                    "db/migration/V48__levels_by_the_rites.sql",
                    "db/migration/V49__levels_by_the_movies_and_the_rites.sql",
                    "db/migration/V50__invite_acceptances.sql",
                    "db/migration/V51__swipe_decision_time.sql",
                    "db/migration/V52__top_ten.sql",
                    "db/migration/V53__honourable_mentions.sql"
                )))
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
    // Pages and scripts travel gzipped: the stylesheet and the script are 300 KB plain, a tenth of that compressed.
    install(Compression) {
        gzip { priority = 1.0 }
        deflate { priority = 0.9; minimumSize(1024) }
    }
    // The stylesheet, the script and the images are kept by the browser for a year: their links carry a version
    // that changes with every start (Accounts.ASSET_VERSION), so a deploy still reaches everyone at once.
    install(CachingHeaders) {
        options { _, content ->
            val type = content.contentType?.withoutParameters()
            if (type != null && (type == ContentType.Text.CSS || type == ContentType.Text.JavaScript || type == ContentType.Application.JavaScript || type.match(ContentType.Image.Any)))
                CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 365 * 24 * 3600, visibility = CacheControl.Visibility.Public))
            else null
        }
    }

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
