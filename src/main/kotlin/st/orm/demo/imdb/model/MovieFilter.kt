package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable

/**
 * The movies a user wants the site to pick for them, chosen on the account
 * page: none with one of the [hiddenTags], none without a tag unless
 * [showsUntagged], and, when [languages] names any, only movies made in one
 * of them (ISO 639 codes, see [MovieLanguage]). The hidden tags are kept
 * rather than the wanted ones, so a tag added later shows until the user
 * hides it. It applies wherever the site
 * picks movies: For You, the home page, Top Movies, Recently Added, the
 * genres and Catch up. What the user looks for, their own lists and a
 * person's movies show everything.
 */
@Serializable
data class MovieFilter(
    val hiddenTags: Set<Tag> = emptySet(),
    val showsUntagged: Boolean = true,
    val languages: Set<String> = emptySet()
) {
    /** Whether this filter lets every movie through. */
    val isNone: Boolean
        get() = this == NONE

    companion object {
        val NONE = MovieFilter()
    }
}
