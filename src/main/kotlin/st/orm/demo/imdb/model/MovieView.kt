package st.orm.demo.imdb.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.FK
import st.orm.PK
import st.orm.Ref
import st.orm.demo.imdb.serialization.InstantAsStringSerializer
import st.orm.template.ref
import java.time.Instant

/**
 * Tracks each visit to a movie detail page, backing the recently-viewed
 * section on the home page. This high-volume, append-only table is the one
 * place the model uses Ref: recording a view needs only the movie's id,
 * so there is no reason to join the full movie graph on every insert.
 *
 * Serializable to demonstrate Ref serialization: the Ref field is marked
 * Contextual so StormSerializers handles it — an unloaded ref serializes
 * as the raw primary key, a loaded ref as the full entity. Instant needs a
 * custom serializer (kotlinx has no built-in java.time support).
 */
@Serializable
data class MovieView(
    @PK val id: Long = 0,
    @FK @Contextual val movie: Ref<Movie>,
    @Serializable(with = InstantAsStringSerializer::class) val viewedAt: Instant
) : Entity<Long> {
    constructor(movie: Movie, viewedAt: Instant) : this(movie = movie.ref(), viewedAt = viewedAt)
}
