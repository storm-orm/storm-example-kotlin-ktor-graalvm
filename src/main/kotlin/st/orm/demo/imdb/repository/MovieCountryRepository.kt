package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.Ref
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.CheckIn_
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieCountry
import st.orm.demo.imdb.model.MovieCountryPk
import st.orm.demo.imdb.model.MovieCountry_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.inRefs
import st.orm.template.less
import java.time.Instant

/** Query result shape: how many movies of a list were made in a country. */
@GenerateMetamodel
data class CountryCount(val country: String, val movies: Long)

interface MovieCountryRepository : EntityRepository<MovieCountry, MovieCountryPk> {

    /** The countries of several movies at once, for the recommendations. */
    fun findByMovies(movies: Collection<Ref<Movie>>): List<MovieCountry> =
        if (movies.isEmpty()) emptyList() else findAll(MovieCountry_.movie inRefs movies)

    /**
     * The countries the movies the user checked in from [from] up to
     * [until] were made in, the most movies first: a year's map. A
     * co-production counts for each of its countries, and a movie watched
     * twice once.
     */
    fun countCheckedIn(user: User, from: Instant, until: Instant): List<CountryCount> =
        select<CountryCount, _, _> { "${MovieCountry_.id.country}, COUNT(DISTINCT ${CheckIn_.movie})" }
            .innerJoin<CheckIn>().on<Movie>()
            .where((CheckIn_.user eq user) and (CheckIn_.watchedAt greaterEq from) and (CheckIn_.watchedAt less until))
            .groupBy(MovieCountry_.id.country)
            .orderByDescending { "COUNT(DISTINCT ${CheckIn_.movie})" }
            .resultList

    /** Gives these movies the [countries] in place of the ones they had. */
    fun replace(movies: Collection<Ref<Movie>>, countries: List<MovieCountry>) {
        removeAll(MovieCountry_.movie inRefs movies)
        insert(countries)
    }
}
