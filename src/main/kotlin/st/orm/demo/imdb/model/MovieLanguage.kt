package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.entityId

data class MovieLanguagePk(
    val movieId: String,
    /** The language's ISO 639 code: the two-letter one where it has one ("hi"), else the three-letter one ("yue"). */
    val language: String
)

/**
 * A language a movie was originally made in, as Wikidata names it. The key
 * is the pair; most movies have one language, some a few.
 */
data class MovieLanguage(
    @PK(generation = NONE) val id: MovieLanguagePk,
    @FK @Persist(insertable = false, updatable = false) val movie: Ref<Movie>
) : Entity<MovieLanguagePk> {
    constructor(movie: Ref<Movie>, language: String) : this(
        id = MovieLanguagePk(movieId = movie.entityId(), language = language),
        movie = movie
    )
}
