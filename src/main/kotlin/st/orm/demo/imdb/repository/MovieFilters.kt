package st.orm.demo.imdb.repository

import st.orm.Data
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.MovieLanguage_
import st.orm.demo.imdb.model.MovieTag
import st.orm.demo.imdb.model.MovieTag_
import st.orm.template.SqlScope
import st.orm.template.inList

/**
 * Keeps a query to the movies [filter] lets through ([MovieFilter]), on any
 * query that reads movies: the movie's tags and languages are related to it
 * by their foreign key, so each condition is a semi-join or an anti-join on
 * the movie, an EXISTS or NOT EXISTS that never repeats a row. A hidden tag
 * is an anti-join; hiding the movies without a tag is a semi-join on any
 * tag; the languages are a semi-join on one of them. No filter adds nothing.
 */
fun <T : Data, R : Any, ID : Any> SqlScope<T, R, ID>.keepTo(filter: MovieFilter?) {
    if (filter == null) return
    if (filter.hiddenTags.isNotEmpty()) {
        antiJoin<MovieTag, Movie> { MovieTag_.id.tag inList filter.hiddenTags.toList() }
    }
    if (!filter.showsUntagged) {
        semiJoin<MovieTag, Movie>()
    }
    if (filter.languages.isNotEmpty()) {
        semiJoin<MovieLanguage, Movie> { MovieLanguage_.id.language inList filter.languages.toList() }
    }
}
