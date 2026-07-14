package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Genre_
import st.orm.demo.imdb.model.MovieGenre
import st.orm.repository.EntityRepository
import st.orm.repository.select

/**
 * Query result shape: a genre together with the number of movies in it,
 * for the genre navigation bar on the home page. Not backed by a database
 * table or view, so it is a plain data class — deliberately not a Data type.
 */
data class GenreMovieCount(
    val genre: Genre,
    val movieCount: Long
)

interface GenreRepository : EntityRepository<Genre, Int> {

    /** Lookup by the unique genre name (a type-safe @UK key lookup). */
    fun findByName(name: String) = findBy(Genre_.name, name)

    fun findAllOrderedByName() =
        select().orderBy(Genre_.name).resultList

    /**
     * All genres with their movie counts for the genre navigation bar.
     * COUNT(*) is a computed expression, so the SELECT clause uses a
     * template; the join, grouping, and ordering stay in code.
     */
    fun findGenresWithMovieCounts() =
        select<GenreMovieCount, _, _> { "${Genre::class}, COUNT(*)" }
            .innerJoin<MovieGenre>().on<Genre>()
            .groupBy(Genre_.id, Genre_.name)
            .orderBy(Genre_.name)
            .resultList
}
