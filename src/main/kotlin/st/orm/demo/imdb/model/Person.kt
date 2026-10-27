package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.PK

/**
 * Bruce Willis, who stepped away from acting in 2022 with aphasia, since
 * diagnosed as frontotemporal dementia: his movies earn a badge of their own.
 */
const val BRUCE_WILLIS = "nm0000246"

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
