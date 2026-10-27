package st.orm.demo.imdb

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import st.orm.demo.imdb.service.AccountService
import st.orm.demo.imdb.service.AttentionService
import st.orm.demo.imdb.service.BadgeService
import st.orm.demo.imdb.service.BrowseService
import st.orm.demo.imdb.service.LevelService
import st.orm.demo.imdb.service.PoolService
import st.orm.demo.imdb.service.RecentlyAddedService
import st.orm.demo.imdb.service.RecommendationService
import st.orm.demo.imdb.service.GalleryRefreshProperties
import st.orm.demo.imdb.service.GitSignIn
import st.orm.demo.imdb.service.HomeService
import st.orm.demo.imdb.service.ListService
import st.orm.demo.imdb.service.CachedImdbDatasets
import st.orm.demo.imdb.service.ImdbDataSync
import st.orm.demo.imdb.service.ImdbDatasets
import st.orm.demo.imdb.service.ImdbSyncProperties
import st.orm.demo.imdb.service.MovieFilterService
import st.orm.demo.imdb.service.MovieNightService
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.MovieService
import st.orm.demo.imdb.service.PersonGalleryService
import st.orm.demo.imdb.service.PersonService
import st.orm.demo.imdb.service.QuizService
import st.orm.demo.imdb.service.MatchService
import st.orm.demo.imdb.service.PlayService
import st.orm.demo.imdb.service.HigherLowerService
import st.orm.demo.imdb.service.ChainService
import st.orm.demo.imdb.service.ConnectService
import st.orm.demo.imdb.service.DailyPuzzleService
import st.orm.demo.imdb.service.PuzzleFactory
import st.orm.demo.imdb.service.CastService
import st.orm.demo.imdb.service.DraftService
import st.orm.demo.imdb.service.SwipeService
import st.orm.demo.imdb.service.SearchService
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.ClubService
import st.orm.demo.imdb.service.CommunityService
import st.orm.demo.imdb.service.InviteService
import st.orm.demo.imdb.service.CatchUpService
import st.orm.demo.imdb.service.CinemaService
import st.orm.demo.imdb.service.InsightsService
import st.orm.demo.imdb.service.StatisticsService
import st.orm.demo.imdb.service.ViewLogProperties
import st.orm.demo.imdb.service.ViewLogService
import st.orm.demo.imdb.service.WallService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.demo.imdb.service.MovieSourceService
import st.orm.demo.imdb.service.StreamingService
import st.orm.demo.imdb.service.TasteOverlap
import st.orm.demo.imdb.service.TmdbClient
import st.orm.demo.imdb.service.TmdbService
import st.orm.demo.imdb.service.WikidataClient
import st.orm.demo.imdb.service.WikidataService
import st.orm.demo.imdb.service.WikipediaSummaryService
import st.orm.demo.imdb.service.WrappedService
import st.orm.demo.imdb.service.configuredProviders
import st.orm.demo.imdb.service.freepodSignIn
import st.orm.demo.imdb.service.galleryRefreshProperties
import st.orm.demo.imdb.service.imdbSyncProperties
import st.orm.demo.imdb.service.objectBucket
import st.orm.demo.imdb.service.viewLogProperties
import st.orm.demo.imdb.web.PosterController
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Service wiring with Ktor's built-in dependency injection. The Storm plugin
 * already registered the ORMTemplate and every repository it auto-registered
 * from the compile-time index, each under its own interface type, so the
 * services resolve their repositories directly.
 */
fun Application.configureDependencies() {
    val syncProperties = environment.config.imdbSyncProperties()
    val galleryRefreshProperties = environment.config.galleryRefreshProperties()
    val viewLogProperties = environment.config.viewLogProperties()
    val configuredProviders = environment.config.configuredProviders()
    val freepodSignIn = environment.config.freepodSignIn()
    val tmdbApiKey = environment.config.propertyOrNull("tmdb.apiKey")?.getString()
    val imageBucket = environment.config.objectBucket()
    dependencies {
        // With an ObservationRegistry in the container, the Storm plugin observes
        // every query (storm.query) and every transaction (storm.transaction); the
        // meter handler turns the observations into timers in the Prometheus registry.
        provide<ObservationRegistry> {
            ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    DefaultMeterObservationHandler(resolve<PrometheusMeterRegistry>()))
            }
        }
        provide<ObjectMapper> { jacksonObjectMapper() }
        provide<ImdbSyncProperties> { syncProperties }
        provide<GalleryRefreshProperties> { galleryRefreshProperties }
        provide<ViewLogProperties> { viewLogProperties }
        provide { ViewLogService(resolve(), resolve(), resolve()) }
        // The git providers the deployment has credentials for; see application.conf (accounts).
        provide { GitSignIn(configuredProviders, resolve()) }
        provide { freepodSignIn }
        provide {
            RecommendationService(
                resolve(), resolve(), resolve(), resolve(), resolve(), resolve(),
                resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(),
                resolve(), resolve(),
            )
        }
        provide { AccountService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { HomeService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { TasteOverlap(resolve(), resolve()) }
        provide { CommunityService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { FriendService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { ClubService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { InviteService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide {
            MovieNightService(
                resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(),
                resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(),
                resolve(), resolve(), resolve(), resolve(),
            )
        }
        provide { ListService(resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { WatchlistService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { MovieSeenService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { CatchUpService(resolve(), resolve()) }
        provide { StatisticsService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { InsightsService(resolve(), resolve(), resolve(), resolve()) }
        provide { WrappedService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { SearchService(resolve(), resolve(), resolve()) }
        provide { BrowseService(resolve(), resolve(), resolve(), resolve()) }
        provide { RecentlyAddedService(resolve()) }
        provide { MovieService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { PersonService(resolve(), resolve()) }
        provide { MatchService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { HigherLowerService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { ChainService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide {
            SwipeService(
                resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(),
                resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(),
            )
        }
        provide { DraftService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { PuzzleFactory(resolve()) }
        provide { DailyPuzzleService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { ConnectService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { LevelService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { PoolService(resolve(), resolve(), resolve()) }
        provide { WallService(resolve(), resolve(), resolve()) }
        provide { AttentionService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { BadgeService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { CastService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { PlayService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { QuizService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { WikipediaSummaryService(resolve(), resolve(), resolve()) }
        // TMDB, for the trailers and for imports of TMDB exports, with the key from application.conf (tmdb).
        provide { TmdbClient(tmdbApiKey, resolve()) }
        provide { TmdbService(resolve(), resolve(), resolve(), resolve()) }
        provide { CinemaService(resolve(), resolve()) }
        provide { MovieSourceService(resolve(), resolve(), resolve()) }
        // Wikidata, for where each movie was made and in what language: its languages and tags.
        provide { WikidataClient(resolve()) }
        provide { WikidataService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { MovieFilterService(resolve(), resolve(), resolve()) }
        provide { StreamingService(resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { PersonGalleryService(resolve(), resolve(), resolve(), resolve(), resolve<GalleryRefreshProperties>().maxAge) }
        // The images are kept in the deployment's bucket, where it has one; see application.conf (images).
        provide { PosterController(resolve(), imageBucket) }
        provide<ImdbDatasets> { CachedImdbDatasets(syncProperties.cacheDirectory, syncProperties.datasetBaseUrl) }
        provide {
            ImdbDataSync(
                resolve(), resolve(), resolve(), resolve(), resolve(),
                resolve(), resolve(), resolve(), resolve(), resolve(), syncProperties.minimumVoteCount,
            )
        }
    }
}
