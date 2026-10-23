package st.orm.demo.imdb.web

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.service.additionDay

@Serializable
data class MovieSearchItem(val id: String, val title: String, val year: Int?)

@Serializable
data class PersonSearchItem(val id: String, val name: String)

/** A movie of the recently added page, under the day it joined the catalogue. */
@Serializable
data class AddedMovieItem(val id: String, val title: String, val year: Int?, val day: String)

/**
 * One keyset window of results plus the opaque cursor for the next window.
 * The cursor exists for client-server communication: it carries the position
 * the scroll continues from, under a fingerprint of the ordering, while the
 * ordering and the window size are stated by the request. Clients treat it as
 * a black box — never parsed, never constructed — and echo it back unchanged
 * to fetch the adjacent window. Server-side code never needs it: Storm's
 * Window exposes typed next()/previous() Scrollables for direct navigation.
 */
@Serializable
data class SearchWindow<T>(val items: List<T>, val nextCursor: String?)

@Serializable
data class SearchSuggestions(
    val movies: List<MovieSearchItem>,
    val persons: List<PersonSearchItem>
)

fun Movie.toSearchItem() = MovieSearchItem(id = id, title = primaryTitle, year = startYear)

fun Person.toSearchItem() = PersonSearchItem(id = id, name = primaryName)

fun MovieAddition.toAddedItem() =
    AddedMovieItem(id = movie.id, title = movie.primaryTitle, year = movie.startYear, day = additionDay())
