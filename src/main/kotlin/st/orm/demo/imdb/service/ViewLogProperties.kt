package st.orm.demo.imdb.service

import io.ktor.server.config.ApplicationConfig
import java.time.LocalTime
import java.time.Period

data class ViewLogProperties(
    /** How long a view stays a row of its own before the nightly fold turns it into a day count. */
    val keep: Period = Period.ofDays(30),
    /** Time of day, in UTC, of the nightly fold; after the IMDB sync, so the two do not compete. */
    val foldAt: LocalTime = LocalTime.of(4, 0)
)

/**
 * Reads the `imdb.viewLog` section of application.conf into [ViewLogProperties],
 * falling back to the data class defaults for any property that is absent.
 */
fun ApplicationConfig.viewLogProperties(): ViewLogProperties {
    val defaults = ViewLogProperties()
    return ViewLogProperties(
        keep = propertyOrNull("imdb.viewLog.keep")?.getString()?.let { Period.parse(it) } ?: defaults.keep,
        foldAt = propertyOrNull("imdb.viewLog.foldAt")?.getString()?.let { LocalTime.parse(it) } ?: defaults.foldAt
    )
}
