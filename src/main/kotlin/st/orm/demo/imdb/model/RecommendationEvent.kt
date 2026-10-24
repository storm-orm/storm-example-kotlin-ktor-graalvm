package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.PK
import st.orm.Ref
import java.time.Instant

/** What a user did with a recommended movie. Stored by name. */
enum class RecommendationEventKind { OPENED, SAVED, SEEN, DISMISSED }

/**
 * A recommendation that led somewhere: the user opened the movie from a
 * recommendation surface, saved it to the watchlist, marked it seen, or
 * said they are not interested. Append-only, so the user and the movie are
 * Refs; the counts per kind on the statistics page are what the events
 * are for.
 */
data class RecommendationEvent(
    @PK val id: Long = 0,
    @FK val user: Ref<User>,
    @FK val movie: Ref<Movie>,
    val kind: RecommendationEventKind,
    val at: Instant
) : Entity<Long>
