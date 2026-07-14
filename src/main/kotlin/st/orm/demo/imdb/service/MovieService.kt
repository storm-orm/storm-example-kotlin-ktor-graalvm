package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RelatedMovie
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.transaction
import java.time.Instant

/** Everything the movie detail page shows. */
data class MovieDetail(
    val movie: Movie,
    val rating: Rating?,
    val genres: List<Genre>,
    val cast: List<Principal>,
    val relatedMovies: List<RelatedMovie>,
    val onWatchlist: Boolean
)

class MovieService(
    private val movieRepository: MovieRepository,
    private val ratingRepository: RatingRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val principalRepository: PrincipalRepository,
    private val movieViewRepository: MovieViewRepository,
    private val watchlistRepository: WatchlistRepository
) {

    /**
     * Loads the movie detail page and records the visit, so the movie shows
     * up in the recently-viewed section on the home page. The view insert
     * and the page reads share one explicit Storm transaction.
     */
    suspend fun viewMovie(movieId: String): MovieDetail? = transaction {
        val movie = movieRepository.findById(movieId) ?: return@transaction null
        movieViewRepository.insert(MovieView(movie = movie, viewedAt = Instant.now()))

        val cast = principalRepository.findCast(movie)
        val castMembers = cast.map { it.person }.distinct()
        val relatedMovies = if (castMembers.isEmpty()) emptyList() else {
            principalRepository.findMoviesSharingCast(castMembers, excludedMovie = movie, limit = RELATED_MOVIES_LIMIT)
        }
        MovieDetail(
            movie = movie,
            rating = ratingRepository.findById(movie),
            genres = movieGenreRepository.findGenres(movie),
            cast = cast,
            relatedMovies = relatedMovies,
            onWatchlist = watchlistRepository.existsById(movie)
        )
    }

    companion object {
        private const val RELATED_MOVIES_LIMIT = 6
    }
}
