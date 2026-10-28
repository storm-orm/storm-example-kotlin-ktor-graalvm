package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.PK

/**
 * A platform a movie can be watched on, like a streaming service, as TMDB
 * names it (with JustWatch's data): keyed by TMDB's id for it, with the path
 * of its logo on TMDB's image server. A movie's TMDB source lists, per
 * country, the ids of the platforms it plays on ([WatchProviders]).
 */
data class WatchProvider(
    @PK(generation = NONE) val id: Int,
    val name: String,
    val logoPath: String?
) : Entity<Int>
