package st.orm.demo.imdb.repository

import java.time.LocalDate
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNight_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.inList

interface MovieNightRepository : EntityRepository<MovieNight, Long> {

    /** A club's movie nights, the latest day first, the latest planned first within a day. */
    /** The next night of any of these clubs, from [today] on; null when none is planned. */
    fun findNextOf(clubs: List<Club>, today: LocalDate): MovieNight? =
        if (clubs.isEmpty()) null
        else select((MovieNight_.club inList clubs) and (MovieNight_.nightOn greaterEq today))
            .orderBy(MovieNight_.nightOn)
            .limit(1)
            .optionalResult

    /** How many nights the user hosted. */
    fun countHostedBy(user: User): Long =
        count(MovieNight_.host eq user)

    fun findOf(club: Club, limit: Int): List<MovieNight> =
        select(MovieNight_.club eq club)
            .orderByDescending(MovieNight_.nightOn, MovieNight_.id)
            .limit(limit)
            .resultList
}
