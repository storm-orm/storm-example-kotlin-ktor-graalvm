package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNightCandidate
import st.orm.demo.imdb.model.MovieNightCandidatePk
import st.orm.demo.imdb.model.MovieNightCandidate_
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface MovieNightCandidateRepository : EntityRepository<MovieNightCandidate, MovieNightCandidatePk> {

    /** A night's candidates, the best first, each with its movie. */
    fun findCandidates(night: MovieNight): List<MovieNightCandidate> =
        select(MovieNightCandidate_.night eq night)
            .orderBy(MovieNightCandidate_.position)
            .resultList
}
