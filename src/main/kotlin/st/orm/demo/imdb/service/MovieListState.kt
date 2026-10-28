package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.serialization.InstantAsStringSerializer
import java.time.Instant

/**
 * Whether a movie is on the user's watchlist, whether the user has seen it
 * and the day of its latest check-in, if it has one, and the thumbs up or down given to it (null without one):
 * the state of the buttons on the movie page, and the answer of every
 * toggle, since checking a movie in takes it off the watchlist.
 */
@Serializable
data class MovieListState(
    val onWatchlist: Boolean,
    val seen: Boolean,
    val liked: Boolean? = null,
    @Serializable(with = InstantAsStringSerializer::class) val seenAt: Instant? = null
) {
    companion object {
        /**
         * The state for a movie on the watchlist or not, seen as [seen] says, or
         * not seen when null, and last checked in on [checkedInAt], when it was.
         */
        fun of(onWatchlist: Boolean, seen: MovieSeen?, checkedInAt: Instant?) =
            MovieListState(onWatchlist = onWatchlist, seen = seen != null, liked = seen?.liked, seenAt = checkedInAt?.takeIf { seen != null })
    }
}
