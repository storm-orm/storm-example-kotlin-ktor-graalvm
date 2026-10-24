package st.orm.demo.imdb.web

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.service.ClubSeen
import st.orm.demo.imdb.service.dayLabel
import st.orm.demo.imdb.service.monthLabel

/**
 * What the seen tags of every list of movies read, in one request: the
 * visitor's seen movies, by id, with the day of a check-in or null for
 * history; the movies friends have seen, with each friend's name, or null
 * for a friend who keeps it to themselves; and the movies members of the
 * visitor's clubs have seen, with each member and their club.
 */
@Serializable
data class SeenMarks(val seen: Map<String, String?>, val friends: Map<String, List<String?>>, val clubs: Map<String, List<ClubSeen>>)

@Serializable
data class MovieSearchItem(val id: String, val title: String, val year: Int?)

@Serializable
data class PersonSearchItem(val id: String, val name: String)

/** A movie of a grouped list: under the day it joined the catalogue, or the month it was checked in. */
@Serializable
data class GroupedMovieItem(val id: String, val title: String, val year: Int?, val group: String)

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

/** A movie of the For You page, with the reasons its card shows. */
@Serializable
data class RecommendedMovieItem(val id: String, val title: String, val year: Int?, val reasons: List<String>)

/** A thumbs up (true), a thumbs down (false), or neither (null) on a seen movie. */
@Serializable
data class Vote(val liked: Boolean?)

fun Movie.toSearchItem() = MovieSearchItem(id = id, title = primaryTitle, year = startYear)

fun Recommendation.toRecommendedItem() =
    RecommendedMovieItem(id = movie.id, title = movie.primaryTitle, year = movie.startYear, reasons = reasons.map { it.text })

fun Person.toSearchItem() = PersonSearchItem(id = id, name = primaryName)

fun MovieAddition.toAddedItem() =
    GroupedMovieItem(id = movie.id, title = movie.primaryTitle, year = movie.startYear, group = dayLabel(addedAt))

fun CheckIn.toCheckInItem() =
    GroupedMovieItem(id = movie.id, title = movie.primaryTitle, year = movie.startYear, group = monthLabel(watchedAt))
