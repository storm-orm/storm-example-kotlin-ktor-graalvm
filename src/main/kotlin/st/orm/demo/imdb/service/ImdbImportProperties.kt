package st.orm.demo.imdb.service

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Path

data class ImdbImportProperties(
    /** Directory where downloaded IMDB dataset files are cached. */
    val cacheDirectory: Path = Path.of("./data"),
    /** Only movies with at least this many votes are imported. */
    val minimumVoteCount: Int = 1000,
    /** Base URL of the public IMDB dataset files. */
    val datasetBaseUrl: String = "https://datasets.imdbws.com"
)

/**
 * Reads the `imdb.import` section of application.conf into [ImdbImportProperties],
 * falling back to the data class defaults for any property that is absent.
 */
fun ApplicationConfig.imdbImportProperties(): ImdbImportProperties {
    val defaults = ImdbImportProperties()
    return ImdbImportProperties(
        cacheDirectory = propertyOrNull("imdb.import.cacheDirectory")?.getString()?.let { Path.of(it) }
            ?: defaults.cacheDirectory,
        minimumVoteCount = propertyOrNull("imdb.import.minimumVoteCount")?.getString()?.toInt()
            ?: defaults.minimumVoteCount,
        datasetBaseUrl = propertyOrNull("imdb.import.datasetBaseUrl")?.getString()
            ?: defaults.datasetBaseUrl
    )
}
