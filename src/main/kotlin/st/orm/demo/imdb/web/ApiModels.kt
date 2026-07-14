package st.orm.demo.imdb.web

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.MovieSummary
import st.orm.demo.imdb.model.PersonSummary

@Serializable
data class MovieSearchItem(val id: String, val title: String, val year: Int?)

@Serializable
data class PersonSearchItem(val id: String, val name: String)

/**
 * One keyset window of results plus the opaque cursor for the next window.
 * The cursor exists for client-server communication: it encodes exactly what
 * the server needs to continue the scroll (key position, window size, and
 * direction). Clients treat it as a black box — never parsed, never
 * constructed — and echo it back unchanged to fetch the adjacent window.
 * Server-side code never needs it: Storm's Window exposes typed
 * next()/previous() Scrollables for direct navigation, and the cursor is
 * merely their serialized form.
 */
@Serializable
data class SearchWindow<T>(val items: List<T>, val nextCursor: String?)

@Serializable
data class SearchSuggestions(
    val movies: List<MovieSearchItem>,
    val persons: List<PersonSearchItem>
)

@Serializable
data class WatchlistState(val onWatchlist: Boolean)

fun MovieSummary.toSearchItem() = MovieSearchItem(id = id, title = primaryTitle, year = startYear)

fun PersonSummary.toSearchItem() = PersonSearchItem(id = id, name = primaryName)
