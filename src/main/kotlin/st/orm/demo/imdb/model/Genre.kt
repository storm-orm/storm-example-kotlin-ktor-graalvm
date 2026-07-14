package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.PK
import st.orm.UK

/**
 * A film genre, normalized from the comma-separated genre list in the
 * IMDB dataset. Serializable so it can round-trip through the statistics
 * cache — immutable data classes cache safely.
 */
@Serializable
data class Genre(
    @PK val id: Int = 0,
    @UK val name: String
) : Entity<Int>
