package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Genre_
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieGenrePk
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.demo.imdb.serialization.BigDecimalAsStringSerializer
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.eq
import java.math.BigDecimal

/**
 * Query result shape: average rating and movie count for a genre. Not
 * backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
@Serializable
data class GenreRatingStatistics(
    val genre: Genre,
    @Serializable(with = BigDecimalAsStringSerializer::class)
    val averageRating: BigDecimal,
    val movieCount: Long
)

interface MovieGenreRepository : EntityRepository<MovieGenre, MovieGenrePk> {

    /** The genres of a single movie, for the movie detail page. */
    fun findGenres(movie: Movie) =
        select(Genre::class)
            .where(MovieGenre_.movie eq movie)
            .orderByAny(Genre_.name)
            .resultList

    /**
     * Top genres by average rating for the statistics page. Demonstrates
     * GROUP BY + HAVING: genres qualify only with enough rated movies.
     * The aggregate expressions live in templates; everything else is code.
     */
    fun findGenreRatingStatistics(minimumMovieCount: Int, limit: Int) =
        select<GenreRatingStatistics, _, _> { "${Genre::class}, AVG(${Rating_.averageRating}), COUNT(*)" }
            .innerJoin<Rating>().on<Movie>()
            .groupByAny(Genre_.id, Genre_.name)
            .having { "COUNT(*) >= $minimumMovieCount" }
            .orderByDescending { "AVG(${Rating_.averageRating})" }
            .limit(limit)
            .resultList
}
