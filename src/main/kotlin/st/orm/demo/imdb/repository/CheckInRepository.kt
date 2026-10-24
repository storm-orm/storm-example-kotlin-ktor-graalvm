package st.orm.demo.imdb.repository

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
import st.orm.template.inList
import st.orm.template.ref
import java.time.Instant

/**
 * Query result shape: a movie and the day of its latest check-in. Not backed
 * by a database table or view, so it is a plain data class — deliberately
 * not a Data type.
 */
data class LatestCheckIn(
    val movie: Ref<Movie>,
    val watchedAt: Instant
)

interface CheckInRepository : EntityRepository<CheckIn, Long> {

    /** All the user's check-ins, each with its movie: what an import matches its days against. */
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
