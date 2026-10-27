package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.Ref
import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.CheckIn_
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.inList
import st.orm.template.less
import st.orm.template.ref
import java.time.Instant

/** Query result shape: how many movies a user checked in, each counted once however often. */
@GenerateMetamodel
data class CheckedMovies(val movies: Long)

/**
 * Query result shape: a movie and the day of its latest check-in. Not backed
 * by a database table or view, so it is a plain data class — deliberately
 * not a Data type.
 */
@GenerateMetamodel
data class LatestCheckIn(
    val movie: Ref<Movie>,
    val watchedAt: Instant
)

/**
 * Query result shape: a movie and the day of its first check-in. Not backed
 * by a database table or view, so it is a plain data class — deliberately
 * not a Data type.
 */
@GenerateMetamodel
data class FirstCheckIn(
    val movie: Ref<Movie>,
    val watchedAt: Instant
)

/**
 * Query result shape: how many check-ins a user made, by the user's id. Not
 * backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
@GenerateMetamodel
data class CheckInCount(
    val user: Ref<User>,
    val checkIns: Long
)

interface CheckInRepository : EntityRepository<CheckIn, Long> {

    /** All the user's check-ins, each with its movie: what an import matches its days against. */
    /** How many movies the user has checked in, each once: a movie watched again counts no more. */
    fun countMovies(user: User): Long =
        select<CheckedMovies, _, _> { "COUNT(DISTINCT ${CheckIn_.movie})" }
            .where(CheckIn_.user eq user)
            .singleResult
            .movies

    fun findAll(user: User): List<CheckIn> =
        findAll(CheckIn_.user eq user)

    /** The user's check-ins of one movie: the movie page's state and a new check-in's day. */
    fun findAll(user: User, movie: Movie): List<CheckIn> =
        findAll((CheckIn_.user eq user) and (CheckIn_.movie eq movie))

    /**
     * The day of the latest check-in of every movie the user checked in: the
     * seen tags and the recommendations. MAX is a computed expression, so the
     * SELECT clause is a template; the movie is its foreign key, so no movie
     * is joined.
     */
    fun findLatestPerMovie(user: User): List<LatestCheckIn> =
        select<LatestCheckIn, _, _> { "${CheckIn_.movie.id}, MAX(${CheckIn_.watchedAt})" }
            .where(CheckIn_.user eq user)
            .groupBy(CheckIn_.movie)
            .resultList

    /**
     * The day of the first check-in of every movie the user checked in: a
     * check-in after it is a movie watched again. The same grouping as
     * [findLatestPerMovie], with MIN.
     */
    fun findFirstPerMovie(user: User): List<FirstCheckIn> =
        select<FirstCheckIn, _, _> { "${CheckIn_.movie}, MIN(${CheckIn_.watchedAt})" }
            .where(CheckIn_.user eq user)
            .groupBy(CheckIn_.movie)
            .resultList

    /** The user's check-ins from [from] up to [until], each with its movie, the earliest first: a year in movies. */
    fun findBetween(user: User, from: Instant, until: Instant): List<CheckIn> =
        select((CheckIn_.user eq user) and (CheckIn_.watchedAt greaterEq from) and (CheckIn_.watchedAt less until))
            .orderBy(CheckIn_.watchedAt, CheckIn_.id)
            .resultList

    /** How many check-ins each of several users made from [from] up to [until]: a year compared with friends'. */
    fun countBetween(users: List<User>, from: Instant, until: Instant): List<CheckInCount> =
        select<CheckInCount, _, _> { "${CheckIn_.user}, COUNT(*)" }
            .where((CheckIn_.user inList users) and (CheckIn_.watchedAt greaterEq from) and (CheckIn_.watchedAt less until))
            .groupBy(CheckIn_.user)
            .resultList

    /**
     * The user's check-ins with keyset scrolling, the latest first
     * ([newestFirst]): the check-ins page, a movie watched twice listed twice.
     */
    fun scroll(user: User, scrollable: Scrollable<CheckIn>): Window<CheckIn> =
        select(CheckIn_.user eq user).scroll(scrollable)

    /** The latest check-ins of several users, across all of them: the friends feed. */
    fun findLatestOf(users: List<User>, limit: Int): List<CheckIn> =
        select(CheckIn_.user inList users)
            .orderByDescending(CheckIn_.watchedAt)
            .limit(limit)
            .resultList

    /** Removes the user's check-ins of a movie, when it is no longer marked seen. */
    fun removeAll(user: User, movie: Movie) {
        removeAll((CheckIn_.user eq user) and (CheckIn_.movie eq movie))
    }

    /** Moves one user's check-ins to another, for the sign-in that joins two accounts. */
    fun moveTo(from: User, into: User) {
        update(findAll(from).map { it.copy(user = into.ref()) })
    }

    companion object {
        /** The latest watch first, then the check-in's id, which addresses a row. */
        fun newestFirst(size: Int): Scrollable<CheckIn> =
            Scrollable.of(CheckIn_.id, size)
                .sortByDescending(CheckIn_.watchedAt)
    }
}
