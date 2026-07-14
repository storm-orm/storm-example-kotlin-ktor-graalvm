package st.orm.demo.imdb.model

import st.orm.DbTable
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Projection

/**
 * A read-only projection of the person table for search results and
 * auto-complete suggestions: only the id and display name are needed.
 */
@DbTable("person")
data class PersonSummary(
    @PK(generation = NONE) val id: String,
    val primaryName: String
) : Projection<String>
