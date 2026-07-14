package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.MovieView_
import st.orm.repository.EntityRepository

interface MovieViewRepository : EntityRepository<MovieView, Long> {

    /**
     * The most recent movie views, newest first. Views hold a Ref to their
     * movie, so this query stays on the view table alone — callers dedupe
     * the refs and fetch the movies they actually need.
     */
    fun findRecentViews(limit: Int) =
        select()
            .orderByDescending(MovieView_.viewedAt, MovieView_.id)
            .limit(limit)
            .resultList
}
