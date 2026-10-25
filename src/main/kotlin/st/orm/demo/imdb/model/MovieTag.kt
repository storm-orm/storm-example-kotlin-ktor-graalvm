package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.entityId

data class MovieTagPk(
    val movieId: String,
    val tag: Tag
)

/**
 * A tag of a movie ([Tag]). The key is the pair, so a movie has a tag once;
 * the movie is a Ref, as the tags are read to filter movies, never to show
 * them (MovieFilters).
 */
data class MovieTag(
    @PK(generation = NONE) val id: MovieTagPk,
    @FK @Persist(insertable = false, updatable = false) val movie: Ref<Movie>
) : Entity<MovieTagPk> {
    constructor(movie: Ref<Movie>, tag: Tag) : this(
        id = MovieTagPk(movieId = movie.entityId(), tag = tag),
        movie = movie
    )
}
