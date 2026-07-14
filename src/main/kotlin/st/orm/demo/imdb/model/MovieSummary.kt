package st.orm.demo.imdb.model

import st.orm.DbTable
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Projection

/**
 * A read-only projection of the movie table for list pages: search results,
 * browse grids, and auto-complete suggestions only need the id (for the
 * poster endpoint and links), the title, and the year — not the full entity.
 */
@DbTable("movie")
data class MovieSummary(
    @PK(generation = NONE) val id: String,
    val primaryTitle: String,
    val startYear: Int?
) : Projection<String>
