package st.orm.demo.imdb.repository

import st.orm.Ref
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieCountry
import st.orm.demo.imdb.model.MovieCountryPk
import st.orm.demo.imdb.model.MovieCountry_
import st.orm.repository.EntityRepository
import st.orm.template.inRefs

interface MovieCountryRepository : EntityRepository<MovieCountry, MovieCountryPk> {

    /** The countries of several movies at once, for the recommendations. */
    fun findByMovies(movies: Collection<Ref<Movie>>): List<MovieCountry> =
        if (movies.isEmpty()) emptyList() else findAll(MovieCountry_.movie inRefs movies)

    /** Gives these movies the [countries] in place of the ones they had. */
    fun replace(movies: Collection<Ref<Movie>>, countries: List<MovieCountry>) {
        removeAll(MovieCountry_.movie inRefs movies)
        insert(countries)
    }
}
