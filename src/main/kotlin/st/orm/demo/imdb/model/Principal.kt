package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist

data class PrincipalPk(
    val movieId: String,
    val ordering: Int
)

/**
 * A cast or crew credit linking a person to a movie. The IMDB billing order
 * (ordering) is unique per movie and forms the composite primary key together
 * with the movie. The person FK is not part of the key and remains insertable.
 *
 * The billing order is also exposed as a read-only field mapped to the same
 * column as the PK component, giving queries a type-safe ordering criterion.
 */
data class Principal(
    @PK(generation = NONE) val id: PrincipalPk,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    @FK val person: Person,
    @Persist(insertable = false, updatable = false) val ordering: Int,
    val category: String,
    val characters: String?
) : Entity<PrincipalPk> {
    constructor(movie: Movie, ordering: Int, person: Person, category: String, characters: String?) : this(
        id = PrincipalPk(movieId = movie.id, ordering = ordering),
        movie = movie,
        person = person,
        ordering = ordering,
        category = category,
        characters = characters
    )
}
