package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.GenerateMetamodel
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieGenrePk
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeen_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.serialization.BigDecimalAsStringSerializer
import st.orm.Ref
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.eq
import st.orm.template.inList
import java.math.BigDecimal

/**
 * Query result shape: average rating and movie count for a genre. Not
 * backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
@Serializable
@GenerateMetamodel
data class GenreRatingStatistics(
    val genre: Genre,
    @Serializable(with = BigDecimalAsStringSerializer::class)
    val averageRating: BigDecimal,
    val movieCount: Long
)

/**
 * Query result shape: how many of the movies a user has seen are of a
 * genre. Not backed by a database table or view, so it is a plain data
 * class — deliberately not a Data type.
 */
@GenerateMetamodel
data class GenreSeenCount(
    val genre: Genre,
    val movieCount: Long
)

/**
 * Query result shape: how many movies of a genre one of several users has
 * seen, by the user's id. Not backed by a database table or view, so it is
 * a plain data class — deliberately not a Data type.
 */
@GenerateMetamodel
data class UserGenreSeenCount(
    val user: Ref<User>,
    val genre: Genre,
    val movieCount: Long
)

interface MovieGenreRepository : EntityRepository<MovieGenre, MovieGenrePk> {

    /** How many movies of each genre each of several users has seen, for what they have in common. */
    fun countSeenPerGenre(users: List<User>): List<UserGenreSeenCount> =
        select<UserGenreSeenCount, _, _> { "${MovieSeen_.user}, ${Genre::class}, COUNT(*)" }
            .innerJoin<MovieSeen>().on<Movie>()
            .where(MovieSeen_.user inList users)
            .groupBy(MovieSeen_.user, MovieGenre_.genre)
            .resultList

    /** The genre links of several movies at once. */
    fun findByMovies(movies: List<Movie>) =
        findAll(MovieGenre_.movie inList movies)

    /** The genres of a single movie, for the movie detail page. */
    fun findGenres(movie: Movie) =
        select(Genre::class)
            .where(MovieGenre_.movie eq movie)
            .orderBy(MovieGenre_.genre.name)
            .resultList

    /**
     * Top genres by average rating for the statistics page. Demonstrates
     * GROUP BY + HAVING: genres qualify only with enough rated movies.
     * The aggregate expressions live in templates; everything else is code.
     */
    fun findGenreRatingStatistics(minimumMovieCount: Int, limit: Int) =
        select<GenreRatingStatistics, _, _> { "${Genre::class}, AVG(${Rating_.averageRating}), COUNT(*)" }
            .innerJoin<Rating>().on<Movie>()
            .groupBy(MovieGenre_.genre)
            .having { "COUNT(*) >= $minimumMovieCount" }
            .orderByDescending { "AVG(${Rating_.averageRating})" }
            .limit(limit)
            .resultList

    /**
     * The genres of the movies the user has seen, the most seen first, for
     * the insights page. The seen list joins on the movie, so the count is
     * the user's alone; a movie of several genres counts for each.
     */
    fun countSeenPerGenre(user: User, limit: Int): List<GenreSeenCount> =
        select<GenreSeenCount, _, _> { "${Genre::class}, COUNT(*)" }
            .innerJoin<MovieSeen>().on<Movie>()
            .where(MovieSeen_.user eq user)
            .groupBy(MovieGenre_.genre)
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList
}
