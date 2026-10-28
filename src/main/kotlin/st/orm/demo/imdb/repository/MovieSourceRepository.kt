package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSource
import st.orm.demo.imdb.model.MovieSource_
import st.orm.repository.EntityRepository
import st.orm.template.eq

/** The sources of every kind, each row read as its own subtype. One kind's own queries are in its repository. */
interface MovieSourceRepository : EntityRepository<MovieSource, Long> {

    /** Where a movie is known: every source, each row as its own subtype. */
    fun findSources(movie: Movie): List<MovieSource> =
        findAll(MovieSource_.movie eq movie)
}
