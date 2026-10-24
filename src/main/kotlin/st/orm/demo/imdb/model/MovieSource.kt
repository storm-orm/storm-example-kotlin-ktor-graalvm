package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Discriminator
import st.orm.Entity
import st.orm.FK
import st.orm.Json
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
 * TMDB, asked when a movie page wants the trailer or the platforms, or an
 * import names a movie by its TMDB id: the TMDB id and the trailer's YouTube
 * key, either null where TMDB has none, the platforms it plays on per
 * country, null where TMDB was asked before they were, and when TMDB was
 * asked, as its terms let the answer be kept six months.
 */
@Discriminator("tmdb")
data class TmdbSource(
    @PK val id: Long = 0,
    @FK override val movie: Ref<Movie>,
    override val externalId: String?,
    val trailerKey: String?,
    @Json val watchProviders: WatchProviders?,
    val fetchedAt: Instant
) : MovieSource

/**
 * Where a movie plays: per country (ISO 3166-1 alpha-2), the ids of the
 * platforms ([WatchProvider]) that stream it or show it free, in TMDB's
 * order. A country where it plays nowhere is left out.
 */
@Serializable
data class WatchProviders(val byCountry: Map<String, List<Int>>)

/** The source a row is of. */
val MovieSource.source: Source
    get() = when (this) {
        is ImdbSource -> Source.IMDB
        is TmdbSource -> Source.TMDB
    }
