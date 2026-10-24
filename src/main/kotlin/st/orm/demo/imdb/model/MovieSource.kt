package st.orm.demo.imdb.model

import st.orm.Discriminator
import st.orm.Entity
import st.orm.FK
import st.orm.PK
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

/** The sources a movie is known by. Each has a [MovieSource] subtype of its own. */
enum class Source {
    IMDB,
    TMDB
}

/**
 * Where a movie is known: one row per source in one table, told apart by its
 * `kind`. Every source gives the movie an id of its own ([externalId]), and
 * what else it gives is a field of its subtype, like TMDB's trailer. Adding a
 * source is adding a subtype, and the compiler points at every `when` that
 * has to handle it. The catalogue is seeded from IMDb's datasets, so a movie's
 * own key is its IMDb id, and its IMDb source names the same id.
 */
@Discriminator(column = "kind")
sealed interface MovieSource : Entity<Long> {
    val movie: Ref<Movie>
    /** The movie's id at the source; null where the source was asked and has no such movie. */
    val externalId: String?
}

/** IMDb, whose datasets the catalogue is synced from: every movie has this source. */
@Discriminator("imdb")
data class ImdbSource(
    @PK val id: Long = 0,
    @FK override val movie: Ref<Movie>,
    override val externalId: String
) : MovieSource {
    constructor(movie: Movie) : this(movie = movie.ref(), externalId = movie.id)
}

/**
 * TMDB, asked when a movie page wants the trailer or an import names a movie
 * by its TMDB id: the TMDB id and the trailer's YouTube key, either null
 * where TMDB has none, and when TMDB was asked, as its terms let the answer
 * be kept six months.
 */
@Discriminator("tmdb")
data class TmdbSource(
    @PK val id: Long = 0,
    @FK override val movie: Ref<Movie>,
    override val externalId: String?,
    val trailerKey: String?,
    val fetchedAt: Instant
) : MovieSource

/** The source a row is of. */
val MovieSource.source: Source
    get() = when (this) {
        is ImdbSource -> Source.IMDB
        is TmdbSource -> Source.TMDB
    }
