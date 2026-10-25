package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.WatchProvider
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.UserWatchProviderRepository
import st.orm.template.transaction

/** A service the user has, with the watched movies it streams and its share of all their streams. */
data class ServiceShare(val service: WatchProvider, val movies: Int, val percent: Int)

/**
 * The share of the user's services among the movies they watched: [counted]
 * of them stream on at least one, out of the [known] whose platforms TMDB
 * has told so far, of all [watched].
 */
data class ServiceShares(val shares: List<ServiceShare>, val counted: Int, val known: Int, val watched: Int)

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
    private val movieSeenRepository: MovieSeenRepository,
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

    /**
     * Which of the user's services the movies they watched stream on in the
     * country, today: per service, the watched movies on it, and its share
     * of all of them, a movie on several services counting for each; a movie
     * on none of them is left out, and so is a service none of them is on. A user who watched many movies has most
     * of them not looked up on TMDB yet, so each call looks up a few dozen
     * more ([LOOKUPS_PER_CALL]) and says how many are known, for the page to
     * ask again until all are.
     */
    suspend fun sharesOfWatched(user: User, country: Country): ServiceShares {
        val services = servicesOf(user)
        val watched = transaction(readOnly = true) { movieSeenRepository.findAll(user) }.map { it.movie }
        val available = tmdbService.watchProviderIds(watched, country.code, maxLookups = LOOKUPS_PER_CALL)
        val serviceIds = services.map { it.id }.toSet()
        val onServices = available.values.map { ids -> ids.filter { it in serviceIds } }.filter { it.isNotEmpty() }
        val counts = onServices.flatten().groupingBy { it }.eachCount()
        val total = counts.values.sum()
        val shares = services
            .filter { it.id in counts }
            .map { ServiceShare(it, counts.getValue(it.id), Math.round(100.0 * counts.getValue(it.id) / total).toInt()) }
            .sortedByDescending { it.movies }
        return ServiceShares(shares, counted = onServices.size, known = available.size, watched = watched.size)
    }

    /** The picks that stream on one of the user's services in the country, in their order. */
    suspend fun keepToServices(user: User, country: Country, picks: List<RecommendedMovie>): List<RecommendedMovie> {
        val services = servicesOf(user).map { it.id }.toSet()
        val available = tmdbService.watchProviderIds(picks.map(RecommendedMovie::movie), country.code)
        return picks.filter { pick -> available[pick.movie.id].orEmpty().any { it in services } }
    }

    private companion object {
        /** The most watched movies one call looks up on TMDB: six at a time, a few seconds' worth. */
        const val LOOKUPS_PER_CALL = 48
    }
}
