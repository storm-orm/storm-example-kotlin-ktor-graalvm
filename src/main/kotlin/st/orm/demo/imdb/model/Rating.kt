package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.math.BigDecimal

/**
 * The IMDB rating for a movie. A dependent one-to-one: the primary key is
 * the foreign key to the movie, so the entity's ID type is Movie itself.
 */
data class Rating(
    @PK(generation = NONE) @FK val movie: Movie,
    val averageRating: BigDecimal,
    val voteCount: Int
) : Entity<Movie>
