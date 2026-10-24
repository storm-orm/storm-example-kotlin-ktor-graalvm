package st.orm.demo.imdb.service

import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.template.transaction

/** The catch-up page: the genres to narrow it to, the chosen one, whether marked movies are left out, and the first window. */
data class CatchUpView(
    val genres: List<Genre>,
    val genre: Genre?,
    val unmarkedOnly: Boolean,
    val window: Window<Rating>
)

/**
 * The catch-up page, for marking the movies seen long ago: the most voted
 * movies first, of every genre or of one, in keyset windows, and with
 * [unmarkedFor] only the movies that user has not marked yet. Marking one
 * there makes it history (MovieSeenService.toggle), never a check-in; the
 * page tags the seen ones from the seen dates, as every list does.
 */
class CatchUpService(
    private val ratingRepository: RatingRepository,
    private val genreRepository: GenreRepository
) {

    suspend fun catchUp(genreName: String?, unmarkedFor: User?): CatchUpView = transaction(readOnly = true) {
        val genre = genreName?.let { genreRepository.findByName(it) }
        val window = ratingRepository.scrollMostVoted(genre, unmarkedFor, RatingRepository.mostVotedFirst(PAGE_SIZE))
        CatchUpView(genreRepository.findAllOrderedByName(), genre, unmarkedFor != null, window)
    }

    /** The next window: same cursor contract as the other scrolls. */
    suspend fun scroll(genreName: String?, unmarkedFor: User?, cursor: String): Window<Rating> = transaction(readOnly = true) {
        val genre = genreName?.let { genreRepository.findByName(it) }
        ratingRepository.scrollMostVoted(genre, unmarkedFor, RatingRepository.mostVotedFirst(PAGE_SIZE).from(cursor))
    }

    companion object {
        private const val PAGE_SIZE = 48
    }
}
