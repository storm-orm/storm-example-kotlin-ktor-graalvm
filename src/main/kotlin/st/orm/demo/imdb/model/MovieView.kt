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
 * Tracks each visit to a movie detail page: the featured movie on the home
 * page is the site's latest view, and the recently-viewed section the
 * visitor's own, read by [user], the account of a browser that came back
 * with its cookie (null for a first page and for crawlers). The movie and
 * the user are Refs: recording a view needs only their ids, so a
 * high-volume append-only insert never joins either.
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
    @Serializable(with = InstantAsStringSerializer::class) val viewedAt: Instant,
    @FK @Contextual val user: Ref<User>? = null
) : Entity<Long> {
    constructor(movie: Movie, viewedAt: Instant, user: User? = null) : this(movie = movie.ref(), viewedAt = viewedAt, user = user?.ref())
}
