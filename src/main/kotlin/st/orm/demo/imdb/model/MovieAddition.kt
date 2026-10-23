package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.Instant

/**
 * A movie's addition to the catalogue: the moment a daily sync added it. The
 * initial import is no addition — the catalogue started with those movies —
 * so only the movies that joined since have one, and the recently added page
 * lists exactly those. A dependent one-to-one, like Rating: the primary key
 * is the foreign key to the movie, so the entity's ID type is Movie itself.
 */
data class MovieAddition(
    @PK(generation = NONE) @FK val movie: Movie,
    val addedAt: Instant
) : Entity<Movie>
