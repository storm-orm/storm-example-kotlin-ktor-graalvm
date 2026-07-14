package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Movie_
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.isNotNull

/**
 * Query result shape: the number of movies released in one decade,
 * e.g. 1990 -> 2412. Not backed by a database table or view, so it is a
 * plain data class — deliberately not a Data type.
 */
@Serializable
data class DecadeMovieCount(
    val decade: Int,
    val movieCount: Long
)

interface MovieRepository : EntityRepository<Movie, String> {

    /**
     * Movies grouped per decade for the statistics page. The decade bucket
     * is a computed expression, so the SELECT and GROUP BY use a template —
     * still with metamodel column references.
     */
    fun countMoviesPerDecade() =
        select<DecadeMovieCount, _, _> { "(${Movie_.startYear} / 10) * 10, COUNT(*)" }
            .where(Movie_.startYear.isNotNull())
            .groupBy { "(${Movie_.startYear} / 10) * 10" }
            .orderBy { "(${Movie_.startYear} / 10) * 10" }
            .resultList
}
