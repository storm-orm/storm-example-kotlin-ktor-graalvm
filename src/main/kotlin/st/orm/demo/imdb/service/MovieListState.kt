package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable

/**
 * Whether a movie is on the user's watchlist and whether the user has seen
 * it: the state of the two buttons on the movie page, and the answer of
 * both toggles, since marking a movie seen takes it off the watchlist.
 */
@Serializable
data class MovieListState(val onWatchlist: Boolean, val seen: Boolean)
