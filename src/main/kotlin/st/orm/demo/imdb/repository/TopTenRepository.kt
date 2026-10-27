package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.TopTen
import st.orm.demo.imdb.model.TopTenPk
import st.orm.demo.imdb.model.TopTen_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.eq

interface TopTenRepository : EntityRepository<TopTen, TopTenPk> {

    /** The user's top ten, the best first. */
    fun findAll(user: User): List<TopTen> =
        select(TopTen_.user eq user)
            .orderBy(TopTen_.position)
            .resultList

    fun contains(user: User, movie: Movie): Boolean =
        existsById(TopTenPk(userId = user.id, movieId = movie.id))

    fun remove(user: User, movie: Movie) {
        removeById(TopTenPk(userId = user.id, movieId = movie.id))
    }
}
