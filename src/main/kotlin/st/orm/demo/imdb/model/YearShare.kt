package st.orm.demo.imdb.model

import st.orm.DbColumn
import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.Instant

/**
 * A year in movies its user shared: the link's random [token], and whose
 * year it is. Anyone with the link sees that year, without the friends it
 * is compared with; a user shares a year once, and the link stays the same.
 * The column is `of_year`, as `year` is a keyword to some databases.
 */
data class YearShare(
    @PK(generation = NONE) val token: String,
    @FK val user: User,
    @DbColumn("of_year") val year: Int,
    val createdAt: Instant
) : Entity<String>
