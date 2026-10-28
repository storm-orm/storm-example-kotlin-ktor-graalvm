package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class MovieListItemPk(
    val listId: Long,
    val movieId: String
)

/**
 * A movie on a list, keyed by the pair, so a movie is on a list once, and
 * who put it there, which a club's list shows. The movie comes with the
 * row, as the list shows it; the list is a Ref, as items are read for lists
 * already known.
 */
data class MovieListItem(
    @PK(generation = NONE) val id: MovieListItemPk,
    @FK @Persist(insertable = false, updatable = false) val list: Ref<MovieList>,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    @FK val addedBy: Ref<User>?,
    val addedAt: Instant
) : Entity<MovieListItemPk> {
    constructor(list: MovieList, movie: Movie, addedBy: User, addedAt: Instant) : this(
        id = MovieListItemPk(listId = list.id, movieId = movie.id),
        list = list.ref(),
        movie = movie,
        addedBy = addedBy.ref(),
        addedAt = addedAt
    )
}
