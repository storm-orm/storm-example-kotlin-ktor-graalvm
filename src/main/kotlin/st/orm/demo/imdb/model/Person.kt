package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.PK

/**
 * An actor, director, or other credited person from the IMDB dataset,
 * keyed by the natural IMDB identifier (nconst, e.g. "nm0000206").
 * Serializable so it can round-trip through the statistics cache.
 */
@Serializable
data class Person(
    @PK(generation = NONE) val id: String,
    val primaryName: String,
    val birthYear: Int?,
    val deathYear: Int?
) : Entity<String>
