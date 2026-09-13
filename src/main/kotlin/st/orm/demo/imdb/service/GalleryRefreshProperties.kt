package st.orm.demo.imdb.service

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

data class GalleryRefreshProperties(
    /** Galleries fetched longer ago than this are re-fetched. */
    val maxAge: Duration = Duration.ofDays(30),
    /** Time between refresh runs. */
    val interval: Duration = Duration.ofHours(6),
    /** Time from startup to the first refresh run. */
    val initialDelay: Duration = Duration.ofHours(1)
)

/**
 * Reads the `imdb.galleryRefresh` section of application.conf into
 * [GalleryRefreshProperties], falling back to the data class defaults for any
 * property that is absent. Durations are written ISO-8601: PT6H, PT30M, P30D.
 */
fun ApplicationConfig.galleryRefreshProperties(): GalleryRefreshProperties {
    val defaults = GalleryRefreshProperties()
    return GalleryRefreshProperties(
        maxAge = duration("imdb.galleryRefresh.maxAge") ?: defaults.maxAge,
        interval = duration("imdb.galleryRefresh.interval") ?: defaults.interval,
        initialDelay = duration("imdb.galleryRefresh.initialDelay") ?: defaults.initialDelay
    )
}

private fun ApplicationConfig.duration(path: String): Duration? =
    propertyOrNull(path)?.getString()?.let { Duration.parse(it) }
