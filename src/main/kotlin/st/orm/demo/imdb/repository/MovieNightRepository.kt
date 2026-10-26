package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNight_
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface MovieNightRepository : EntityRepository<MovieNight, Long> {

    /** A club's movie nights, the latest day first, the latest planned first within a day. */
    fun findOf(club: Club, limit: Int): List<MovieNight> =
        select(MovieNight_.club eq club)
            .orderByDescending(MovieNight_.nightOn, MovieNight_.id)
            .limit(limit)
            .resultList
}
