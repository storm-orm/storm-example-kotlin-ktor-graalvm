package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.Instant

/**
 * A movie saved to the personal watchlist. The primary key is the foreign
 * key to the movie, which makes saving the same movie twice a key violation —
 * exactly the semantics a watchlist toggle needs.
 */
data class Watchlist(
    @PK(generation = NONE) @FK val movie: Movie,
    val addedAt: Instant
) : Entity<Movie>
