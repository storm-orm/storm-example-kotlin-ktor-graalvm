package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable

/**
 * Whether a movie is on the user's watchlist, whether the user has seen it,
 * and the thumbs up or down given to it (null without one): the state of
 * the buttons on the movie page, and the answer of every toggle, since
 * marking a movie seen takes it off the watchlist.
 */
@Serializable
data class MovieListState(val onWatchlist: Boolean, val seen: Boolean, val liked: Boolean? = null)
