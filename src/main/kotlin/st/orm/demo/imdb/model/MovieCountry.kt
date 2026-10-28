package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.entityId

data class MovieCountryPk(
    val movieId: String,
    /** The country's ISO 3166-1 alpha-2 code: "US". */
    val country: String
)

/**
 * A country a movie was made in, as Wikidata names it. The key is the pair;
 * a co-production has a few.
 */
data class MovieCountry(
    @PK(generation = NONE) val id: MovieCountryPk,
    @FK @Persist(insertable = false, updatable = false) val movie: Ref<Movie>
) : Entity<MovieCountryPk> {
    constructor(movie: Ref<Movie>, country: String) : this(
        id = MovieCountryPk(movieId = movie.entityId(), country = country),
        movie = movie
    )
}
