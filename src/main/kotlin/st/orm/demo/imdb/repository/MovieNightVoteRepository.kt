package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNightVote
import st.orm.demo.imdb.model.MovieNightVotePk
import st.orm.demo.imdb.model.MovieNightVote_
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface MovieNightVoteRepository : EntityRepository<MovieNightVote, MovieNightVotePk> {

    /** A night's votes, each with its member, the first cast first. */
    fun findVotes(night: MovieNight): List<MovieNightVote> =
        select(MovieNightVote_.night eq night)
            .orderBy(MovieNightVote_.votedAt)
            .resultList
}
