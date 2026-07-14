package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist

data class MovieGenrePk(
    val movieId: String,
    val genreId: Int
)

/**
 * Junction between movies and genres. Both FK columns are part of the
 * composite primary key, so the entity fields exist purely as join metadata
 * and are excluded from inserts and updates.
 */
data class MovieGenre(
    @PK(generation = NONE) val id: MovieGenrePk,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    @FK @Persist(insertable = false, updatable = false) val genre: Genre
) : Entity<MovieGenrePk> {
    constructor(movie: Movie, genre: Genre) : this(
        id = MovieGenrePk(movieId = movie.id, genreId = genre.id),
        movie = movie,
        genre = genre
    )
}
