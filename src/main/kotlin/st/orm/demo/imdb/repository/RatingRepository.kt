package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeen_
import st.orm.demo.imdb.model.Movie_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.demo.imdb.model.User
import st.orm.Ref
import st.orm.Scrollable
import st.orm.Window
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.inRefs
import st.orm.template.isNotNull

/** Sort options for the top movies page. */
enum class TopMoviesSort { RATING, YEAR }

interface RatingRepository : EntityRepository<Rating, Movie> {

    /**
     * The highest rated movies that [filter] lets through. The vote floor
     * keeps obscure titles with a handful of enthusiastic voters out of the
     * list. Each rating carries its full movie via the entity graph — one
     * query, no N+1.
     */
    fun findTopRated(minimumVoteCount: Int, filter: MovieFilter?, limit: Int) =
        select {
            where(Rating_.voteCount greaterEq minimumVoteCount)
            keepTo(filter)
            orderByDescending(Rating_.averageRating)
            limit(limit)
        }.resultList

    /**
     * The best-rated movies of a genre, with the same vote floor: the
     * candidates a visitor's favourite genres bring to the recommendations,
     * among the movies [filter] lets through.
     */
    fun findTopRatedInGenre(genre: Ref<Genre>, minimumVoteCount: Int, filter: MovieFilter?, limit: Int) =
        select {
            innerJoin<MovieGenre, Movie>()
            where((MovieGenre_.genre eq genre) and (Rating_.voteCount greaterEq minimumVoteCount))
            keepTo(filter)
            orderByDescending(Rating_.averageRating)
            limit(limit)
        }.resultList

    /** The ratings of several movies at once, each carrying its movie. */
    fun findByMovies(movies: List<Ref<Movie>>) =
        findAll(Rating_.movie inRefs movies)

    /**
     * The top movies page: optionally filtered by genre, sorted by rating
     * or release year, among the movies [filter] lets through. Demonstrates
     * query composition — the genre join and the sort order are decided by
     * plain Kotlin conditionals inside the query block.
     */
    fun findTopMovies(
        genre: Genre?,
        sortBy: TopMoviesSort,
        filter: MovieFilter?,
        minimumVoteCount: Int,
        limit: Int
    ) =
        select {
            where(Rating_.voteCount greaterEq minimumVoteCount)
            if (genre != null) {
                innerJoin<MovieGenre, Movie>()
                where(MovieGenre_.genre eq genre)
            }
            keepTo(filter)
            when (sortBy) {
                TopMoviesSort.RATING -> orderByDescending(Rating_.averageRating)
                TopMoviesSort.YEAR -> {
                    where(Movie_.startYear.isNotNull())
                    orderByDescending(Movie_.startYear, Rating_.averageRating)
                }
            }
            limit(limit)
        }.resultList

    /**
     * The ratings with keyset scrolling, the most voted first, optionally of
     * one genre, and optionally only the movies a user has not marked seen:
     * the catch-up page, where the movies most people saw come first
     * ([mostVotedFirst]). As on the top movies page, the genre join is there
     * only when a genre is asked for; the user's seen list is an anti-join,
     * a NOT EXISTS with the user on the join, so the movies they marked never
     * reach the page, and [filter] keeps it to the movies it lets through.
     * Each rating carries its movie.
     */
    fun scrollMostVoted(genre: Genre?, unseenBy: User?, filter: MovieFilter?, scrollable: Scrollable<Rating>): Window<Rating> =
        select {
            if (genre != null) {
                innerJoin<MovieGenre, Movie>()
                where(MovieGenre_.genre eq genre)
            }
            if (unseenBy != null) {
                antiJoin<MovieSeen, Movie> { MovieSeen_.user eq unseenBy }
            }
            keepTo(filter)
        }.narrow<Rating>().scroll(scrollable)

    companion object {
        /** The most voted first, then the movie's id, which addresses a row (the index of V12). */
        fun mostVotedFirst(size: Int): Scrollable<Rating> =
            Scrollable.of(Rating_.movie.id, size)
                .sortByDescending(Rating_.voteCount)
    }

}
