package st.orm.demo.imdb.repository

import st.orm.Page
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.MovieSeen_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface MovieSeenRepository : EntityRepository<MovieSeen, MovieSeenPk> {

    /** Whether the user has marked the movie as seen: a lookup of the pair key. */
    fun contains(user: User, movie: Movie): Boolean =
        existsById(MovieSeenPk(userId = user.id, movieId = movie.id))

    /** Everything the user has seen, in no particular order: the seeds of the recommendations. */
    fun findAll(user: User): List<MovieSeen> =
        findAll(MovieSeen_.user eq user)

    /**
     * One page of the movies the user has seen, the most recently marked
     * first, with offset-based pagination. Page numbers are 0-based.
     */
    fun findPage(user: User, pageNumber: Int, pageSize: Int): Page<MovieSeen> =
        select(MovieSeen_.user eq user)
            .orderByDescending(MovieSeen_.seenAt)
            .page(pageNumber, pageSize)

    /** The movies the user marked as seen most recently, for the home page. */
    fun findMostRecent(user: User, limit: Int): List<MovieSeen> =
        select(MovieSeen_.user eq user)
            .orderByDescending(MovieSeen_.seenAt)
            .limit(limit)
            .resultList

    /**
     * Moves one user's seen list to another, for the sign-in that finds the
     * provider account linked to an existing user while the visitor already
     * marked movies anonymously; see WatchlistRepository.moveTo.
     */
    fun moveTo(from: User, into: User) {
        val entries = select(MovieSeen_.user eq from).resultList
        val present = select(MovieSeen_.user eq into).resultList.map { it.movie.id }.toSet()
        insert(entries.filterNot { it.movie.id in present }.map { MovieSeen(into, it.movie, it.seenAt) })
        removeAll(MovieSeen_.user eq from)
    }
}
