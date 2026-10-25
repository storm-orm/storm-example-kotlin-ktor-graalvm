package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.model.MovieAddition_
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.greaterEq
import java.time.Instant

/**
 * The sync adds older movies too, as they gather the votes the catalogue
 * asks for, and those are not new: the recently added queries take the
 * first release year that counts as recent ([st.orm.demo.imdb.service.recentYear])
 * and leave the older additions out.
 */
interface MovieAdditionRepository : EntityRepository<MovieAddition, Movie> {

    /**
     * The additions of the movies released in or after [fromYear], with
     * keyset scrolling, for the recently added page: the newest first and,
     * among the movies one sync run added, the most voted first
     * ([newestFirst]). The movie comes with the addition; the rating is
     * joined for the vote count, and every synced movie has one, so the
     * inner join drops none. [filter] keeps it to the movies it lets through.
     */
    fun scrollAdditions(fromYear: Int, filter: MovieFilter?, scrollable: Scrollable<MovieAddition>): Window<MovieAddition> =
        select {
            innerJoin<Rating, Movie>()
            where(MovieAddition_.movie.startYear greaterEq fromYear)
            keepTo(filter)
        }.narrow<MovieAddition>().scroll(scrollable)

    /**
     * The movies released in or after [fromYear] that joined the catalogue
     * since a moment, newest first: the new arrivals among the recommendations.
     */
    fun findAddedSince(since: Instant, fromYear: Int, limit: Int): List<MovieAddition> =
        select((MovieAddition_.addedAt greaterEq since) and (MovieAddition_.movie.startYear greaterEq fromYear))
            .orderByDescending(MovieAddition_.addedAt)
            .limit(limit)
            .resultList

    companion object {
        /**
         * The newest addition first, the most voted among the additions of
         * one moment next, then the movie's id, which addresses a row: the
         * order of the recently added page ([scrollAdditions]).
         */
        fun newestFirst(size: Int): Scrollable<MovieAddition> =
            Scrollable.of(MovieAddition_.movie.id, size)
                .sortByDescending(MovieAddition_.addedAt)
                .sortByDescending(Rating_.voteCount)
    }
}
