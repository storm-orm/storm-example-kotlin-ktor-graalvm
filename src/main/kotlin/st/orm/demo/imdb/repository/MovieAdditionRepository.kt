package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.model.MovieAddition_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.repository.EntityRepository

interface MovieAdditionRepository : EntityRepository<MovieAddition, Movie> {

    /**
     * The additions with keyset scrolling, for the recently added page: the
     * newest first and, among the movies one sync run added, the most voted
     * first ([newestFirst]). The movie comes with the addition; the rating is
     * joined for the vote count, and every synced movie has one, so the inner
     * join drops none.
     */
    fun scrollAdditions(scrollable: Scrollable<MovieAddition>): Window<MovieAddition> =
        select()
            .innerJoin<Rating>().on<Movie>()
            .narrow<MovieAddition>()
            .scroll(scrollable)

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
