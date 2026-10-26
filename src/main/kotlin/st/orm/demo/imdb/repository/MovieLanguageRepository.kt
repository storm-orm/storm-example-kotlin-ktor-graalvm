package st.orm.demo.imdb.repository

import st.orm.GenerateMetamodel
import st.orm.Ref
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.CheckIn_
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.MovieLanguagePk
import st.orm.demo.imdb.model.MovieLanguage_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.inRefs
import st.orm.template.less
import java.time.Instant

/** Query result shape: how many movies of the catalogue, or of a list, were made in a language. */
@GenerateMetamodel
data class LanguageCount(val language: String, val movies: Long)

interface MovieLanguageRepository : EntityRepository<MovieLanguage, MovieLanguagePk> {

    /** The languages of several movies at once, for the recommendations. */
    fun findByMovies(movies: Collection<Ref<Movie>>): List<MovieLanguage> =
        if (movies.isEmpty()) emptyList() else findAll(MovieLanguage_.movie inRefs movies)

    /** The languages a movie was made in, as ISO 639 codes. */
    fun findLanguages(movie: Movie): List<String> =
        findAll(MovieLanguage_.movie eq movie).map { it.id.language }

    /** The languages of the catalogue, the most movies first: the choices of the account page. */
    fun findLanguageCounts(): List<LanguageCount> =
        select<LanguageCount, _, _> { "${MovieLanguage_.id.language}, COUNT(*)" }
            .groupBy(MovieLanguage_.id.language)
            .orderByDescending { "COUNT(*)" }
            .resultList

    /**
     * The languages of the movies the user checked in from [from] up to
     * [until], the most movies first: a year's languages. A movie watched
     * twice counts once.
     */
    fun countCheckedIn(user: User, from: Instant, until: Instant): List<LanguageCount> =
        select<LanguageCount, _, _> { "${MovieLanguage_.id.language}, COUNT(DISTINCT ${CheckIn_.movie})" }
            .innerJoin<CheckIn>().on<Movie>()
            .where((CheckIn_.user eq user) and (CheckIn_.watchedAt greaterEq from) and (CheckIn_.watchedAt less until))
            .groupBy(MovieLanguage_.id.language)
            .orderByDescending { "COUNT(DISTINCT ${CheckIn_.movie})" }
            .resultList

    /** Gives these movies the [languages] in place of the ones they had. */
    fun replace(movies: Collection<Ref<Movie>>, languages: List<MovieLanguage>) {
        removeAll(MovieLanguage_.movie inRefs movies)
        insert(languages)
    }
}
