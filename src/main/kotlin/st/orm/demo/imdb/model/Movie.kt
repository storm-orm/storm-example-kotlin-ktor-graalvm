package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.QueryColumn

/**
 * A movie from the IMDB dataset, keyed by its natural IMDB identifier
 * (tconst, e.g. "tt0133093"). Serializable so it can travel inside a
 * serialized Ref (see MovieView) — immutable data classes cache safely.
 *
 * The titles without their accents are query columns: the database stores
 * them (V2), the searches match and rank them, and no movie carries them, so
 * they are never selected or written and the sync builds a movie from the
 * dataset as before. Each column names the function that computed it, so a
 * title compared with it is folded by that same function, in the same
 * statement, and "amelie" finds Amélie.
 */
@Serializable
@QueryColumn(field = "primaryTitleFolded", type = String::class, function = "fold_accents")
@QueryColumn(field = "originalTitleFolded", type = String::class, function = "fold_accents")
data class Movie(
    @PK(generation = NONE) val id: String,
    val primaryTitle: String,
    val originalTitle: String,
    val startYear: Int?,
    val runtimeMinutes: Int?
) : Entity<String>
