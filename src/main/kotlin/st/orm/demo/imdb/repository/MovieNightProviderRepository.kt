package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNightProvider
import st.orm.demo.imdb.model.MovieNightProviderPk
import st.orm.demo.imdb.model.MovieNightProvider_
import st.orm.demo.imdb.model.WatchProvider
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface MovieNightProviderRepository : EntityRepository<MovieNightProvider, MovieNightProviderPk> {

    /** The services a night is watched on, with their names and logos. */
    fun findServices(night: MovieNight): List<WatchProvider> =
        findAll(MovieNightProvider_.night eq night).map { it.watchProvider }
}
