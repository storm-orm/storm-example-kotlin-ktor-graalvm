package st.orm.demo.imdb.repository

import st.orm.Ref
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieTag
import st.orm.demo.imdb.model.MovieTagPk
import st.orm.demo.imdb.model.MovieTag_
import st.orm.repository.EntityRepository
import st.orm.template.inRefs

interface MovieTagRepository : EntityRepository<MovieTag, MovieTagPk> {

    /** Gives these movies the [tags] in place of the ones they had: a movie without one in [tags] has none. */
    fun replace(movies: Collection<Ref<Movie>>, tags: List<MovieTag>) {
        removeAll(MovieTag_.movie inRefs movies)
        insert(tags)
    }
}
