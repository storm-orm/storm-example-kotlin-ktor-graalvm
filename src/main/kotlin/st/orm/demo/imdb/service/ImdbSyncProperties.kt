package st.orm.demo.imdb.service

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Path
import java.time.LocalTime

data class ImdbSyncProperties(
    /** Whether the dataset is imported into an empty database and synced daily. */
    val enabled: Boolean = true,
    /** Movies join the catalogue once they have at least this many votes. */
    val minimumVoteCount: Int = 1000,
    /** Time of day, in UTC, of the daily sync. IMDB publishes the dataset shortly after midnight UTC. */
    val dailyAt: LocalTime = LocalTime.of(3, 0),
    /** Directory where downloaded IMDB dataset files are cached. */
    val cacheDirectory: Path = Path.of("./data"),
    /** Base URL of the public IMDB dataset files. */
    val datasetBaseUrl: String = "https://datasets.imdbws.com"
)

/**
 * Reads the `imdb.sync` section of application.conf into [ImdbSyncProperties],
 * falling back to the data class defaults for any property that is absent.
 */
fun ApplicationConfig.imdbSyncProperties(): ImdbSyncProperties {
    val defaults = ImdbSyncProperties()
    return ImdbSyncProperties(
        enabled = propertyOrNull("imdb.sync.enabled")?.getString()?.toBoolean()
            ?: defaults.enabled,
        minimumVoteCount = propertyOrNull("imdb.sync.minimumVoteCount")?.getString()?.toInt()
            ?: defaults.minimumVoteCount,
        dailyAt = propertyOrNull("imdb.sync.dailyAt")?.getString()?.let { LocalTime.parse(it) }
            ?: defaults.dailyAt,
        cacheDirectory = propertyOrNull("imdb.sync.cacheDirectory")?.getString()?.let { Path.of(it) }
            ?: defaults.cacheDirectory,
        datasetBaseUrl = propertyOrNull("imdb.sync.datasetBaseUrl")?.getString()
            ?: defaults.datasetBaseUrl
    )
}
