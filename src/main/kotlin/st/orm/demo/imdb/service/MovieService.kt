package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeenPk
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.RecommendationEventKind
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RelatedMovie
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.transaction
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

/**
 * A person's credits on one movie, listed once: an actor who also directs
 * holds a credit per role, which the page shows as one card.
 */
data class CastCredit(
    val person: Person,
    /** The roles in billing order: the character played, or else the job. */
    val roles: List<String>
) {
    /** The roles as the card shows them, separated by a slash. */
    val role: String get() = roles.joinToString(" / ")
}

/** Everything the movie detail page shows. */
data class MovieDetail(
    val movie: Movie,
    val rating: Rating?,
    val genres: List<Genre>,
    val cast: List<CastCredit>,
    val relatedMovies: List<RelatedMovie>,
    /** The state of the visitor's two lists for this movie. */
    val lists: MovieListState
) {
    /**
     * The movie in a line, for where only a line fits: the share message,
     * and the card a shared link unfurls into. "136 min · Action, Sci-Fi ·
     * ★ 8.7 from 2,000,000 votes", with what the movie has; the year is in
     * the title the line goes with.
     */
    val caption: String
        get() = listOfNotNull(
            movie.runtimeMinutes?.let { "$it min" },
            genres.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
            rating?.let { "★ ${it.averageRating} from ${VOTES.format(it.voteCount)} votes" }
        ).joinToString(" · ")
}

private val VOTES: NumberFormat = NumberFormat.getIntegerInstance(Locale.ENGLISH)

class MovieService(
    private val movieRepository: MovieRepository,
    private val ratingRepository: RatingRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val principalRepository: PrincipalRepository,
    private val movieViewRepository: MovieViewRepository,
    private val watchlistRepository: WatchlistRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val userRepository: UserRepository,
    private val recommendationService: RecommendationService
) {

    /**
     * Loads the movie detail page for the visitor and records the visit:
     * the site's latest view is the featured movie on the home page, and a
     * [returning] visitor's views, a browser that came back with its account
     * cookie, are their recently viewed. Only those record the account, and
     * write its row: a first page and a crawler, which keeps no cookies, do
     * not. A visit that came from a recommendation surface is recorded as
     * such, if the movie is one of the visitor's recommendations. The
     * inserts and the page reads share one explicit Storm transaction.
     */
    suspend fun viewMovie(movieId: String, user: User, returning: Boolean, fromRecommendation: Boolean): MovieDetail? = transaction {
        val movie = movieRepository.findById(movieId) ?: return@transaction null
        val now = Instant.now()
        val viewer = if (returning) userRepository.touch(user, now) else null
        movieViewRepository.insert(MovieView(movie = movie, viewedAt = now, user = viewer))
        if (fromRecommendation) recommendationService.record(user, movie, RecommendationEventKind.OPENED)

        val credits = principalRepository.findCast(movie)
        val cast = castCredits(credits)
        val castMembers = cast.map { it.person }
        val relatedMovies = if (castMembers.isEmpty()) emptyList() else {
            principalRepository.findMoviesSharingCast(castMembers, excludedMovie = movie, limit = RELATED_MOVIES_LIMIT)
        }
        MovieDetail(
            movie = movie,
            rating = ratingRepository.findById(movie),
            genres = movieGenreRepository.findGenres(movie),
            cast = cast,
            relatedMovies = relatedMovies,
            lists = MovieListState.of(
                onWatchlist = watchlistRepository.contains(user, movie),
                seen = movieSeenRepository.findById(MovieSeenPk(userId = user.id, movieId = movie.id))
            )
        )
    }

    /**
     * Folds the credits into one per person, at the person's first billing
     * position, with each distinct role once.
     */
    private fun castCredits(credits: List<Principal>): List<CastCredit> =
        credits.groupBy { it.person.id }.values.map { personCredits ->
            CastCredit(
                person = personCredits.first().person,
                roles = personCredits.map { it.characters ?: it.category }.distinct()
            )
        }

    companion object {
        private const val RELATED_MOVIES_LIMIT = 6
    }
}
