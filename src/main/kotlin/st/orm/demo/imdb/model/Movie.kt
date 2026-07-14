package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.PK

/**
 * A movie from the IMDB dataset, keyed by its natural IMDB identifier
 * (tconst, e.g. "tt0133093"). Serializable so it can travel inside a
 * serialized Ref (see MovieView) — immutable data classes cache safely.
 */
@Serializable
data class Movie(
    @PK(generation = NONE) val id: String,
    val primaryTitle: String,
    val originalTitle: String,
    val startYear: Int?,
    val runtimeMinutes: Int?
) : Entity<String>
