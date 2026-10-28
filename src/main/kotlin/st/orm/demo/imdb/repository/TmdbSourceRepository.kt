package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.TmdbSource
import st.orm.demo.imdb.model.TmdbSource_
import st.orm.repository.EntityRepository
import st.orm.template.eq
import st.orm.template.inList
import st.orm.template.less
import java.time.Instant

/** The TMDB sources: the rows of the kind 'tmdb', which only these queries see. */
interface TmdbSourceRepository : EntityRepository<TmdbSource, Long> {

    /** What TMDB answered about a movie, if it was asked. */
    fun findByMovie(movie: Movie): TmdbSource? =
        find(TmdbSource_.movie eq movie)

    /** What TMDB answered about these movies, for the ones that were asked: the posters of For You. */
    fun findByMovies(movies: Collection<Movie>): List<TmdbSource> =
        if (movies.isEmpty()) emptyList() else findAll(TmdbSource_.movie inList movies)

    /** The sources of the movies with these TMDB ids, for an import that names movies by them. */
    fun findByExternalIds(externalIds: Collection<String>): List<TmdbSource> =
        if (externalIds.isEmpty()) emptyList() else findAll(TmdbSource_.externalId inList externalIds)

    /** Stores what TMDB answered about a movie, in place of what it answered before. */
    fun save(source: TmdbSource) {
        val stored = find(TmdbSource_.movie eq source.movie)
        if (stored == null) insert(source) else update(source.copy(id = stored.id))
    }

    /** Removes what TMDB answered before a moment: its terms let it be kept six months at most. */
    fun removeFetchedBefore(before: Instant) {
        removeAll(TmdbSource_.fetchedAt less before)
    }
}
