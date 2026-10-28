package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.Ref
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieList
import st.orm.demo.imdb.model.MovieListItem
import st.orm.demo.imdb.model.MovieListItemPk
import st.orm.demo.imdb.model.MovieListItem_
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList

/**
 * Query result shape: how many movies a list has, by the list's id. Not
 * backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
@GenerateMetamodel
data class ListMovieCount(
    val list: Ref<MovieList>,
    val movies: Long
)

interface MovieListItemRepository : EntityRepository<MovieListItem, MovieListItemPk> {

    /** A list's movies, the first added first. */
    fun findItems(list: MovieList): List<MovieListItem> =
        select(MovieListItem_.list eq list)
            .orderBy(MovieListItem_.addedAt, MovieListItem_.movie)
            .resultList

    /** The movies of several lists, the latest added first: the posters a list's card shows. */
    fun findItems(lists: List<MovieList>): List<MovieListItem> =
        if (lists.isEmpty()) emptyList() else select(MovieListItem_.list inList lists)
            .orderByDescending(MovieListItem_.addedAt)
            .resultList

    /** How many movies each of several lists has; a list without any is left out. */
    fun countMovies(lists: List<MovieList>): List<ListMovieCount> =
        if (lists.isEmpty()) emptyList() else select<ListMovieCount, _, _> { "${MovieListItem_.list}, COUNT(*)" }
            .where(MovieListItem_.list inList lists)
            .groupBy(MovieListItem_.list)
            .resultList

    /** Which of several lists have the movie: the ticks of a movie page's list menu. */
    fun findListsWith(lists: List<MovieList>, movie: Movie): List<MovieListItem> =
        if (lists.isEmpty()) emptyList() else findAll((MovieListItem_.list inList lists) and (MovieListItem_.movie eq movie))
}
