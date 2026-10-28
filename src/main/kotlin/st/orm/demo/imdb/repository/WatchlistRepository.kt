package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.Page
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.model.WatchlistPk
import st.orm.demo.imdb.model.Watchlist_
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList

/**
 * Query result shape: a movie and on how many of several users' watchlists
 * it is. Not backed by a database table or view, so it is a plain data
 * class — deliberately not a Data type.
 */
@GenerateMetamodel
data class WatchlistCount(
    val movie: Movie,
    val users: Long
)

interface WatchlistRepository : EntityRepository<Watchlist, WatchlistPk> {

    /** Whether the movie is on the user's watchlist: a lookup of the pair key. */
    fun contains(user: User, movie: Movie): Boolean =
        existsById(WatchlistPk(userId = user.id, movieId = movie.id))

    /** How many movies are on the user's watchlist, for the insights page. */
    fun countFor(user: User): Long =
        count(Watchlist_.user eq user)

    /** Everything on the user's watchlist, in no particular order: the seeds of the recommendations. */
    fun findAll(user: User): List<Watchlist> =
        findAll(Watchlist_.user eq user)

    /**
     * The movies on several users' watchlists, each with on how many: what
     * a club would like to see. One grouped query, joined to the movie it
     * shows.
     */
    fun countListedBy(users: List<User>): List<WatchlistCount> =
        if (users.isEmpty()) emptyList() else select<WatchlistCount, _, _> { "${Movie::class}, COUNT(*)" }
            .where(Watchlist_.user inList users)
            .groupBy(Watchlist_.movie)
            .resultList

    /** Takes the movie off the user's watchlist, if it is on it. */
    fun remove(user: User, movie: Movie) {
        removeAll((Watchlist_.user eq user) and (Watchlist_.movie eq movie))
    }

    /** Takes several movies off the user's watchlist at once: the ones an import found watched since they were put on it. */
    fun removeAll(user: User, movies: List<Movie>) {
        if (movies.isNotEmpty()) removeAll((Watchlist_.user eq user) and (Watchlist_.movie inList movies))
    }

    /**
     * One page of the user's watchlist, newest first, with offset-based
     * pagination. Page numbers are 0-based.
     */
    fun findPage(user: User, pageNumber: Int, pageSize: Int): Page<Watchlist> =
        select(Watchlist_.user eq user)
            .orderByDescending(Watchlist_.addedAt)
            .page(pageNumber, pageSize)

    /** The user's most recently added watchlist entries, for the home page. */
    fun findMostRecent(user: User, limit: Int): List<Watchlist> =
        select(Watchlist_.user eq user)
            .orderByDescending(Watchlist_.addedAt)
            .limit(limit)
            .resultList

    /**
     * Moves one user's watchlist to another, for the sign-in that finds the
     * provider account linked to an existing user while the visitor already
     * saved movies anonymously: the movies the existing user lacks are
     * inserted with their original moments, and the anonymous rows go.
     */
    fun moveTo(from: User, into: User) {
        val entries = select(Watchlist_.user eq from).resultList
        val present = select(Watchlist_.user eq into).resultList.map { it.movie.id }.toSet()
        insert(entries.filterNot { it.movie.id in present }.map { Watchlist(into, it.movie, it.addedAt) })
        removeAll(Watchlist_.user eq from)
    }
}
