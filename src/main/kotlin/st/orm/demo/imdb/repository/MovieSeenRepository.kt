package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.Scrollable
import st.orm.Window
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
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList
import java.math.BigDecimal
import java.time.Instant

/**
 * Query result shape: a movie the user has seen, with its IMDb rating's
 * columns, which a movie the sync has not rated yet lacks. Not backed by a
 * database table or view, so it is a plain data class — deliberately not a
 * Data type.
 */
@GenerateMetamodel
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
 * Query result shape: a movie one of several users has seen, and who, as
 * ids. Not backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
@GenerateMetamodel
data class SeenBy(
    val movie: Ref<Movie>,
    val user: Ref<User>
)

/**
 * Query result shape: a movie and how many of several users have seen it.
 * Not backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
@GenerateMetamodel
data class MovieSeenCount(
    val movie: Movie,
    val seenCount: Long
)

/**
 * Query result shape: how many movies a user has seen, or has seen that
 * another user has seen too, by the user's id. Not backed by a database
 * table or view, so it is a plain data class — deliberately not a Data type.
 */
@GenerateMetamodel
data class SeenCount(
    val user: Ref<User>,
    val movieCount: Long
)

interface MovieSeenRepository : EntityRepository<MovieSeen, MovieSeenPk> {

    /** How many movies each of several users has seen. */
    fun countSeen(users: List<User>): List<SeenCount> =
        select<SeenCount, _, _> { "${MovieSeen_.user}, COUNT(*)" }
            .where(MovieSeen_.user inList users)
            .groupBy(MovieSeen_.user)
            .resultList

    /**
     * How many of the movies each of several users has seen the [user] has
     * seen too: what they have in common. The other users' seen rows are
     * counted, kept by a semi-join to the user's own seen rows on the movie,
     * the table joined to itself, each side its own alias.
     */
    fun countSeenInCommon(user: User, others: List<User>): List<SeenCount> =
        select<SeenCount, _, _> { "${MovieSeen_.user}, COUNT(*)" }
            .semiJoin<MovieSeen>().on<Movie> { MovieSeen_.user eq user }
            .where(MovieSeen_.user inList others)
            .groupBy(MovieSeen_.user)
            .resultList

    /** Whether the user has marked the movie as seen: a lookup of the pair key. */
    fun contains(user: User, movie: Movie): Boolean =
        existsById(MovieSeenPk(userId = user.id, movieId = movie.id))

    /**
     * Which movies several users have seen, and who, as ids: the friend
     * markers, and the seen tags for one user. The movie and the user are both selected as foreign keys, so
     * the query stays on the seen list and loads neither.
     */
    fun findSeenBy(users: List<User>): List<SeenBy> =
        select<SeenBy, _, _> { "${MovieSeen_.movie.id}, ${MovieSeen_.user}" }
            .where(MovieSeen_.user inList users)
            .resultList

    /**
     * The movies most of several users have seen, those seen by at least
     * [minimum] of them, the most seen first: what a club watched most. One
     * grouped query on the seen list, joined to the movie it shows.
     */
    fun countSeenBy(users: List<User>, minimum: Long, limit: Int): List<MovieSeenCount> =
        select<MovieSeenCount, _, _> { "${Movie::class}, COUNT(*)" }
            .where(MovieSeen_.user inList users)
            .groupBy(MovieSeen_.movie)
            .having { "COUNT(*) >= $minimum" }
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList

    /** The movies the user has seen, as ids: the seen tags of every list. */
    fun findSeenMovies(user: User): List<Ref<Movie>> =
        findSeenBy(listOf(user)).map { it.movie }

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
     * The movies the user has seen with keyset scrolling, the latest marked
     * or watched first ([newestFirst]): the seen page, one window at a time
     * however long the list grows.
     */
    fun scroll(user: User, scrollable: Scrollable<MovieSeen>): Window<MovieSeen> =
        select(MovieSeen_.user eq user).scroll(scrollable)

    /** How many movies the user has seen. */
    fun countFor(user: User): Long =
        count(MovieSeen_.user eq user)

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

    companion object {
        /** The latest marked first, then the movie's id, which addresses a row within one user's list. */
        fun newestFirst(size: Int): Scrollable<MovieSeen> =
            Scrollable.of(MovieSeen_.movie.id, size)
                .sortByDescending(MovieSeen_.seenAt)
    }
}
