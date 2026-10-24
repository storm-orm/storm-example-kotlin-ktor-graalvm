package st.orm.demo.imdb.repository

import st.orm.Page
import st.orm.Ref
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.MovieSeen_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.eq
import java.math.BigDecimal
import java.time.Instant

/**
 * Query result shape: a movie the user has seen, with its IMDb rating's
 * columns, which a movie the sync has not rated yet lacks. Not backed by a
 * database table or view, so it is a plain data class — deliberately not a
 * Data type.
 */
data class SeenWithRating(
    val seen: MovieSeen,
    val averageRating: BigDecimal?,
    val voteCount: Int?
) {
    /** The rating as the entity, for what reads ratings; null for a movie without one. */
    val rating: Rating?
        get() = if (averageRating != null && voteCount != null) Rating(seen.movie, averageRating, voteCount) else null
}

/**
 * Query result shape: a movie the user has seen, as its id, and when they
 * marked it. Not backed by a database table or view, so it is a plain data
 * class — deliberately not a Data type.
 */
data class SeenDate(
    val movie: Ref<Movie>,
    val seenAt: Instant
)

interface MovieSeenRepository : EntityRepository<MovieSeen, MovieSeenPk> {

    /** Whether the user has marked the movie as seen: a lookup of the pair key. */
    fun contains(user: User, movie: Movie): Boolean =
        existsById(MovieSeenPk(userId = user.id, movieId = movie.id))

    /**
     * When the user marked each movie seen, for the seen tag on every list
     * of movies. The movie is selected as its foreign key, a Ref, so the
     * query stays on the seen list and loads no movie.
     */
    fun findSeenDates(user: User): List<SeenDate> =
        select<SeenDate, _, _> { "${MovieSeen_.movie.id}, ${MovieSeen_.seenAt}" }
            .where(MovieSeen_.user eq user)
            .resultList

    /** Everything the user has seen, in no particular order: the seeds of the recommendations. */
    fun findAll(user: User): List<MovieSeen> =
        findAll(MovieSeen_.user eq user)

    /**
     * Everything the user has seen, each with its IMDb rating, for the
     * insights page: the rating's columns are selected beside the seen row,
     * from a left join, so one query brings both, and a movie without a
     * rating still counts as seen.
     */
    fun findAllWithRatings(user: User): List<SeenWithRating> =
        select<SeenWithRating, _, _> { "${MovieSeen::class}, ${Rating_.averageRating}, ${Rating_.voteCount}" }
            .leftJoin<Rating>().on<Movie>()
            .where(MovieSeen_.user eq user)
            .resultList

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
