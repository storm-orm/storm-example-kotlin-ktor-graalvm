package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.LanguageCount
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.transaction

/** A language the catalogue has movies in: its ISO 639 code, its English name and how many movies. */
data class Language(val code: String, val name: String, val movies: Long)

/**
 * The movies a user wants picked for them ([MovieFilter]), chosen on the
 * account page: the languages to choose from, and saving the choice, which
 * recomputes the user's recommendations among the movies it lets through.
 */
class MovieFilterService(
    private val userRepository: UserRepository,
    private val movieLanguageRepository: MovieLanguageRepository,
    private val recommendationService: RecommendationService
) {
    /**
     * The languages to choose from: the ones with [LANGUAGE_MINIMUM_MOVIES]
     * movies at least, the most movies first, and the ones [chosen] already.
     */
    suspend fun languages(chosen: Set<String>): List<Language> = transaction(readOnly = true) {
        movieLanguageRepository.findLanguageCounts()
            .filter { it.movies >= LANGUAGE_MINIMUM_MOVIES || it.language in chosen }
            .map { it.toLanguage() }
    }

    /** Saves the user's filter, null when it lets every movie through, and recomputes their recommendations. */
    suspend fun updateFilter(user: User, filter: MovieFilter) {
        transaction { userRepository.update(user.copy(movieFilter = filter.takeUnless { it.isNone })) }
        recommendationService.refreshLater(user)
    }

    private fun LanguageCount.toLanguage() = Language(language, Languages.nameOf(language), movies)

    companion object {
        private const val LANGUAGE_MINIMUM_MOVIES = 25
    }
}
