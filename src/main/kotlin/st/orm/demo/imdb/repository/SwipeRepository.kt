package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Swipe
import st.orm.demo.imdb.model.SwipePk
import st.orm.demo.imdb.model.Swipe_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.eq
import st.orm.template.inList

interface SwipeRepository : EntityRepository<Swipe, SwipePk> {

    /** The user's swipes, each with the way it went. */
    fun findAll(user: User): List<Swipe> =
        findAll(Swipe_.user eq user)

    /**
     * The swipes of several others on the movies the user swiped too: what
     * their taste is compared on. The others' rows are kept by a semi-join to
     * the user's own rows on the movie, the table joined to itself.
     */
    fun findSharedWith(user: User, others: List<User>): List<Swipe> =
        if (others.isEmpty()) emptyList() else select(Swipe_.user inList others)
            .semiJoin<Swipe>().on<Movie> { Swipe_.user eq user }
            .resultList
}
