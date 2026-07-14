package st.orm.demo.imdb.repository

import st.orm.Page
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.model.Watchlist_
import st.orm.repository.EntityRepository

interface WatchlistRepository : EntityRepository<Watchlist, Movie> {

    /**
     * One page of the watchlist, newest first, with offset-based pagination.
     * Page numbers are 0-based.
     */
    fun findPage(pageNumber: Int, pageSize: Int) =
        select()
            .orderByDescending(Watchlist_.addedAt)
            .page(pageNumber, pageSize)

    /** The most recently added watchlist entries for the home page. */
    fun findMostRecent(limit: Int) =
        select()
            .orderByDescending(Watchlist_.addedAt)
            .limit(limit)
            .resultList
}
