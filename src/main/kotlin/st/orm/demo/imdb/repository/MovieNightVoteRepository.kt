package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNightVote
import st.orm.demo.imdb.model.MovieNightVotePk
import st.orm.demo.imdb.model.MovieNightVote_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList

interface MovieNightVoteRepository : EntityRepository<MovieNightVote, MovieNightVotePk> {

    /** A night's votes, each with its member, the first cast first. */
    /** The user's votes in any of these nights: which they answered. */
    fun findByUser(user: User, nights: List<MovieNight>): List<MovieNightVote> =
        if (nights.isEmpty()) emptyList() else findAll((MovieNightVote_.user eq user) and (MovieNightVote_.night inList nights))

    fun findVotes(night: MovieNight): List<MovieNightVote> =
        select(MovieNightVote_.night eq night)
            .orderBy(MovieNightVote_.votedAt)
            .resultList
}
