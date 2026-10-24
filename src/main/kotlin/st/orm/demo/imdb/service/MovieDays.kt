package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The movies of one day or month, in the order the page lists them: the
 * movies that joined the catalogue on a day, on the recently added page,
 * and the movies checked in during a month, on the seen page.
 */
data class MovieGroup(
    val label: String,
    val movies: List<Movie>
)

/** A day as the pages head a group of movies: "23 October 2026", in UTC. */
fun dayLabel(at: Instant): String = DAY_LABEL.format(at)

/** A month as the pages head a group of movies: "October 2026", in UTC. */
fun monthLabel(at: Instant): String = MONTH_LABEL.format(at)

/** Groups movies under their label, keeping the order they come in: the groups of a window, newest first. */
fun <T> List<T>.groupedBy(label: (T) -> String, movie: (T) -> Movie): List<MovieGroup> =
    groupBy(label).map { (label, items) -> MovieGroup(label, items.map(movie)) }

private val DAY_LABEL: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

private val MONTH_LABEL: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)
