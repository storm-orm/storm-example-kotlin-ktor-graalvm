package st.orm.demo.imdb.repository

import st.orm.Ref
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.MovieView_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.less
import st.orm.template.ref
import java.time.Instant
import java.time.LocalDate

/**
 * Query result shape: a movie and how often it was viewed, keyed by the
 * view's foreign key, so the count needs no join to the movie table. Not
 * backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
data class MovieViewCount(
    val movie: Ref<Movie>,
    val viewCount: Long
)

/**
 * Query result shape: the views of a movie on one day, as the fold of the
 * view log counts them. Not backed by a database table or view, so it is
 * a plain data class — deliberately not a Data type.
 */
data class MovieViewDayCount(
    val movie: Ref<Movie>,
    val day: LocalDate,
    val viewCount: Long
)

interface MovieViewRepository : EntityRepository<MovieView, Long> {

    /**
     * The user's most recent movie views, newest first: the recently-viewed
     * section on the home page, read by the partial index on the account
     * and the moment (V9). Callers dedupe the refs, as a movie viewed twice
     * is listed once.
     */
    fun findRecentViews(user: User, limit: Int) =
        select(MovieView_.user eq user)
            .orderByDescending(MovieView_.viewedAt, MovieView_.id)
            .limit(limit)
            .resultList

    /**
     * Moves one user's views to another, for the sign-in that merges the
     * browser's anonymous account into the signed-in one: the views of the
     * last month at most, since older ones are folded, so one batch.
     */
    fun moveTo(from: User, into: User) {
        update(findAll(MovieView_.user eq from).map { it.copy(user = into.ref()) })
    }

    /**
     * The most viewed movies since a moment: what the site's visitors are
     * looking at, the trending term of the recommendations. COUNT(*) is a
     * computed expression, so the SELECT clause uses a template; the
     * filter, grouping and ordering stay in code.
     */
    fun findMostViewedSince(since: Instant, limit: Int) =
        select<MovieViewCount, _, _> { "${MovieView_.movie}, COUNT(*)" }
            .where(MovieView_.viewedAt greaterEq since)
            .groupBy(MovieView_.movie)
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList

    /**
     * The views before a moment, counted per movie and day: what the fold
     * of the view log turns into day rows. The day is the view's moment
     * cast to a date, an expression, so the SELECT and GROUP BY clauses
     * are templates; the filter stays in code.
     */
    fun countPerMovieAndDayBefore(before: Instant) =
        select<MovieViewDayCount, _, _> { "${MovieView_.movie}, CAST(${MovieView_.viewedAt} AS DATE), COUNT(*)" }
            .where(MovieView_.viewedAt less before)
            .groupBy { "${MovieView_.movie}, CAST(${MovieView_.viewedAt} AS DATE)" }
            .resultList

    /** Removes the views before a moment, once they are folded. */
    fun removeBefore(before: Instant): Int =
        removeAll(MovieView_.viewedAt less before)
}
