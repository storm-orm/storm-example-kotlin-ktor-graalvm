package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.MovieList
import st.orm.demo.imdb.model.MovieList_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList
import st.orm.template.isNotNull
import st.orm.template.isNull
import st.orm.template.neq

interface MovieListRepository : EntityRepository<MovieList, Long> {

    /** The user's own lists, not a club's, the most recently changed first. */
    fun findOwn(user: User): List<MovieList> =
        select((MovieList_.owner eq user) and MovieList_.club.isNull())
            .orderByDescending(MovieList_.updatedAt)
            .resultList

    /** The lists of several clubs, the most recently changed first: the lists of the user's clubs. */
    fun findOfClubs(clubs: List<Club>): List<MovieList> =
        if (clubs.isEmpty()) emptyList() else select(MovieList_.club inList clubs)
            .orderByDescending(MovieList_.updatedAt)
            .resultList

    /**
     * The public lists of the others who signed in, the most recently
     * changed first: the lists page's lists to discover. A club's list is
     * its members' alone.
     */
    fun findOthers(user: User, limit: Int): List<MovieList> =
        select((MovieList_.owner neq user) and MovieList_.club.isNull() and MovieList_.owner.name.isNotNull())
            .orderByDescending(MovieList_.updatedAt)
            .limit(limit)
            .resultList
}
