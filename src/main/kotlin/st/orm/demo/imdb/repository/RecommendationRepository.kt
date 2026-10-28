package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.model.RecommendationPk
import st.orm.demo.imdb.model.Recommendation_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList
import st.orm.template.ref
import java.time.Instant

interface RecommendationRepository : EntityRepository<Recommendation, RecommendationPk> {

    /** Whether the movie is among the user's recommendations: a lookup of the pair key. */
    fun contains(user: User, movie: Movie): Boolean =
        existsById(RecommendationPk(userId = user.id, movieId = movie.id))

    /**
     * All of a user's recommendations, best first. A user has at most a
     * hundred (RecommendationService), so the home page reads them in one
     * query and groups the shelves itself.
     */
    fun findAll(user: User): List<Recommendation> =
        select(Recommendation_.user eq user)
            .orderByDescending(Recommendation_.score)
            .resultList

    /** The recommendations of several users, each row naming its user: what suits a club. */
    fun findAllOf(users: List<User>): List<Recommendation> =
        if (users.isEmpty()) emptyList() else findAll(Recommendation_.user inList users)

    /** The user's recommendations with keyset scrolling, for the For You page ([bestFirst]). */
    fun scroll(user: User, scrollable: Scrollable<Recommendation>): Window<Recommendation> =
        select(Recommendation_.user eq user)
            .scroll(scrollable)

    /**
     * Replaces the user's recommendations with a newly computed set: the old
     * rows go, the new ones come in one batch. Called inside the transaction
     * that computed them, so a page never sees the list half replaced.
     */
    fun replace(user: User, recommendations: List<Recommendation>) {
        removeAll(Recommendation_.user eq user)
        insert(recommendations)
    }

    /**
     * Counts the recommendations as shown once more, in one batched update
     * of the rows that still exist. The rows were read by a page a moment
     * ago, and a recomputation may have replaced them since; so the rows
     * are first locked by key, which makes a recomputation wait for this
     * write or leaves this write with the rows that remain, and a batch
     * update never meets a row that is gone.
     */
    fun recordShown(user: User, shown: List<Recommendation>, at: Instant) {
        if (shown.isEmpty()) return
        val present = selectRef((Recommendation_.user eq user) and (Recommendation_.movie inList shown.map { it.movie }))
            .forUpdate()
            .resultList
            .toSet()
        update(shown.filter { it.ref() in present }.map { it.shown(at) })
    }

    /**
     * Starts the count afresh for a recommendation the user opened: it
     * worked, so it should not fade for having been shown.
     */
    fun resetShown(user: User, movie: Movie) {
        findById(RecommendationPk(userId = user.id, movieId = movie.id))?.let { update(it.copy(shownCount = 0)) }
    }

    companion object {
        /** Best first, then the movie's id, which addresses a row; the index on the table matches. */
        fun bestFirst(size: Int): Scrollable<Recommendation> =
            Scrollable.of(Recommendation_.movie.id, size)
                .sortByDescending(Recommendation_.score)
    }
}
