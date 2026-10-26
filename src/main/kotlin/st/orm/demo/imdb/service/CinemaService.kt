package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.template.ref
import st.orm.template.transaction

/** What plays in a country's cinemas now: all of it, for the tags on the posters, and what the visitor's filter lets through, for the home page's row. */
data class CinemaNow(val movies: List<Movie>, val picks: List<Movie>)

/**
 * The movies in the cinemas of the visitor's country now, as TMDB knows
 * them ([TmdbService.nowPlayingIn], kept for half a day per country), the
 * most popular first: the "In cinemas" tag on every poster of one, and the
 * home page's row, kept to the movies the visitor's filter lets through.
 */
class CinemaService(
    private val tmdbService: TmdbService,
    private val movieRepository: MovieRepository
) {

    suspend fun nowPlaying(country: Country?, filter: MovieFilter?): CinemaNow {
        if (country == null) return CinemaNow(emptyList(), emptyList())
        val ids = tmdbService.nowPlayingIn(country.code)
        if (ids.isEmpty()) return CinemaNow(emptyList(), emptyList())
        return transaction(readOnly = true) {
            val byId = movieRepository.findAllById(ids).associateBy { it.id }
            val movies = ids.mapNotNull(byId::get)
            val kept = if (filter == null || filter.isNone) null else movieRepository.findMatching(movies.map { it.ref() }, filter)
            CinemaNow(movies, if (kept == null) movies else movies.filter { it.ref() in kept })
        }
    }
}
