package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.Movie_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.repository.EntityRepository
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.isNotNull

/** Sort options for the top movies page. */
enum class TopMoviesSort { RATING, YEAR }

interface RatingRepository : EntityRepository<Rating, Movie> {

    /**
     * The highest rated movies. The vote floor keeps obscure titles with a
     * handful of enthusiastic voters out of the list. Each rating carries
     * its full movie via the entity graph — one query, no N+1.
     */
    fun findTopRated(minimumVoteCount: Int, limit: Int) =
        select(Rating_.voteCount greaterEq minimumVoteCount)
            .orderByDescending(Rating_.averageRating)
            .limit(limit)
            .resultList

    /**
     * The top movies page: optionally filtered by genre, sorted by rating
     * or release year. Demonstrates query composition — the genre join and
     * the sort order are decided by plain Kotlin conditionals inside the
     * query block.
     */
    fun findTopMovies(
        genre: Genre?,
        sortBy: TopMoviesSort,
        minimumVoteCount: Int,
        limit: Int
    ) =
        select {
            where(Rating_.voteCount greaterEq minimumVoteCount)
            if (genre != null) {
                innerJoin<MovieGenre, Movie>()
                whereAny(MovieGenre_.genre eq genre)
            }
            when (sortBy) {
                TopMoviesSort.RATING -> orderByDescending(Rating_.averageRating)
                TopMoviesSort.YEAR -> {
                    whereAny(Movie_.startYear.isNotNull())
                    orderByDescendingAny(Movie_.startYear, Rating_.averageRating)
                }
            }
            limit(limit)
        }.resultList
}
