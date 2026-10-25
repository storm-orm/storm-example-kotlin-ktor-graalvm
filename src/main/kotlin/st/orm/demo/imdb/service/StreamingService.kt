package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.WatchProvider
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.UserWatchProviderRepository
import st.orm.template.transaction

/**
 * The streaming services a user has: ticked on the account page among the
 * ones active in their country, shown as logos on the posters of For You
 * where a pick streams on them, and what For You can keep to. Where a movie
 * streams comes from TMDB ([TmdbService]).
 */
class StreamingService(
    private val userRepository: UserRepository,
    private val userWatchProviderRepository: UserWatchProviderRepository,
    private val movieRepository: MovieRepository,
    private val tmdbService: TmdbService
) {
    /** The services active in a country, the most watched there first, to choose from. */
    suspend fun servicesIn(country: Country): List<WatchProvider> =
        tmdbService.watchProvidersIn(country.code)

    /** The services the user has. */
    suspend fun servicesOf(user: User): List<WatchProvider> = transaction(readOnly = true) {
        userWatchProviderRepository.findAll(user)
    }

    /**
     * Sets the services the user has to the ones with these ids among the
     * services of the country. Services are a country's, so a user who left
     * the country to the browser gets this one: a browser in another
     * language would otherwise make For You look for them elsewhere.
     */
    suspend fun updateServices(user: User, country: Country, ids: Set<Int>) {
        val chosen = servicesIn(country).filter { it.id in ids }
        transaction {
            if (user.country == null) userRepository.update(user.copy(country = country.code))
            userWatchProviderRepository.replace(user, chosen)
        }
    }

    /**
     * The user's services each of the movies with these ids streams on in
     * the country, by movie id, for the logos on the posters; a movie on
     * none of them is left out.
     */
    suspend fun servicesByMovie(user: User, country: Country, movieIds: Collection<String>): Map<String, List<WatchProvider>> {
        val services = servicesOf(user).associateBy { it.id }
        if (services.isEmpty()) return emptyMap()
        val movies = transaction(readOnly = true) { movieRepository.findAllById(movieIds) }
        return tmdbService.watchProviderIds(movies, country.code)
            .mapValues { (_, ids) -> ids.mapNotNull(services::get) }
            .filterValues { it.isNotEmpty() }
    }

    /** The picks that stream on one of the user's services in the country, in their order. */
    suspend fun keepToServices(user: User, country: Country, picks: List<RecommendedMovie>): List<RecommendedMovie> {
        val services = servicesOf(user).map { it.id }.toSet()
        val available = tmdbService.watchProviderIds(picks.map(RecommendedMovie::movie), country.code)
        return picks.filter { pick -> available[pick.movie.id].orEmpty().any { it in services } }
    }
}
