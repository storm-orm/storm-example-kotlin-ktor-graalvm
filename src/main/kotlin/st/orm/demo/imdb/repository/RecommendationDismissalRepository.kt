package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.RecommendationDismissal
import st.orm.demo.imdb.model.RecommendationDismissalPk
import st.orm.demo.imdb.model.RecommendationDismissal_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface RecommendationDismissalRepository : EntityRepository<RecommendationDismissal, RecommendationDismissalPk> {

    /** Whether the user dismissed the movie: a lookup of the pair key. */
    fun contains(user: User, movie: Movie): Boolean =
        existsById(RecommendationDismissalPk(userId = user.id, movieId = movie.id))

    /** Everything the user dismissed, in no particular order: negative seeds of the recommendations. */
    fun findAll(user: User): List<RecommendationDismissal> =
        findAll(RecommendationDismissal_.user eq user)

    /**
     * Moves one user's dismissals to another, for the sign-in that merges
     * an anonymous account into an existing one; see WatchlistRepository.moveTo.
     */
    fun moveTo(from: User, into: User) {
        val dismissals = findAll(from)
        val present = findAll(into).map { it.movie.id }.toSet()
        insert(dismissals.filterNot { it.movie.id in present }.map { RecommendationDismissal(into, it.movie, it.dismissedAt) })
        removeAll(RecommendationDismissal_.user eq from)
    }
}
