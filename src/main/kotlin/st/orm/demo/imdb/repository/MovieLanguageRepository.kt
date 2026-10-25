package st.orm.demo.imdb.repository

import st.orm.Ref
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.MovieLanguagePk
import st.orm.demo.imdb.model.MovieLanguage_
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.eq
import st.orm.template.inRefs

/** Query result shape: how many movies of the catalogue were made in a language. */
data class LanguageCount(val language: String, val movies: Long)

interface MovieLanguageRepository : EntityRepository<MovieLanguage, MovieLanguagePk> {

    /** The languages a movie was made in, as ISO 639 codes. */
    fun findLanguages(movie: Movie): List<String> =
        findAll(MovieLanguage_.movie eq movie).map { it.id.language }

    /** The languages of the catalogue, the most movies first: the choices of the account page. */
    fun findLanguageCounts(): List<LanguageCount> =
        select<LanguageCount, _, _> { "${MovieLanguage_.id.language}, COUNT(*)" }
            .groupBy(MovieLanguage_.id.language)
            .orderByDescending { "COUNT(*)" }
            .resultList

    /** Gives these movies the [languages] in place of the ones they had. */
    fun replace(movies: Collection<Ref<Movie>>, languages: List<MovieLanguage>) {
        removeAll(MovieLanguage_.movie inRefs movies)
        insert(languages)
    }
}
