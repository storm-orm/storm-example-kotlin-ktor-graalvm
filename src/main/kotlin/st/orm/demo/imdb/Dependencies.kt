package st.orm.demo.imdb

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.ObservationRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import st.orm.micrometer.OtelDatabaseObservationConvention
import st.orm.micrometer.StormQueryObservationContext
import st.orm.demo.imdb.service.BrowseService
import st.orm.demo.imdb.service.HomeService
import st.orm.demo.imdb.service.ImdbDataImporter
import st.orm.demo.imdb.service.MovieService
import st.orm.demo.imdb.service.PersonGalleryService
import st.orm.demo.imdb.service.PersonService
import st.orm.demo.imdb.service.SearchService
import st.orm.demo.imdb.service.StatisticsService
import st.orm.demo.imdb.service.TopMoviesService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.demo.imdb.service.WikipediaSummaryService
import st.orm.demo.imdb.service.imdbImportProperties
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
    val importProperties = environment.config.imdbImportProperties()
    dependencies {
        // With an ObservationRegistry in the container, the Storm plugin
        // observes every query (storm.query); the meter handler turns the
        // observations into timers in the Prometheus registry.
        provide<ObservationRegistry> {
            ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    DefaultMeterObservationHandler(resolve<PrometheusMeterRegistry>()))
            }
        }
        // Observations report the OpenTelemetry database semantic conventions, so the spans
        // surface in the database views of OTLP-capable backends.
        provide<ObservationConvention<StormQueryObservationContext>> {
            OtelDatabaseObservationConvention("postgresql")
        }
        provide<ObjectMapper> { jacksonObjectMapper() }
        provide { importProperties }
        provide { HomeService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { TopMoviesService(resolve(), resolve()) }
        provide { WatchlistService(resolve(), resolve()) }
        provide { StatisticsService(resolve(), resolve(), resolve()) }
        provide { SearchService(resolve(), resolve()) }
        provide { BrowseService(resolve(), resolve(), resolve()) }
        provide { MovieService(resolve(), resolve(), resolve(), resolve(), resolve(), resolve()) }
        provide { PersonService(resolve(), resolve()) }
        provide { WikipediaSummaryService(resolve(), resolve(), resolve()) }
        provide { PersonGalleryService(resolve(), resolve(), resolve(), resolve()) }
        provide { PosterController(resolve()) }
        provide {
            ImdbDataImporter(
                resolve(), resolve(), resolve(), resolve(), resolve(),
                resolve(), resolve(), resolve(), resolve(),
            )
        }
    }
}
