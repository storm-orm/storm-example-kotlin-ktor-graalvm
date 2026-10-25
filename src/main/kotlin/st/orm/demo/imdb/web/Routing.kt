package st.orm.demo.imdb.web

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
import st.orm.demo.imdb.service.AccountService
import st.orm.demo.imdb.service.BrowseService
import st.orm.demo.imdb.service.CatchUpService
import st.orm.demo.imdb.service.FreepodSignIn
import st.orm.demo.imdb.service.GitSignIn
import st.orm.demo.imdb.service.HomeService
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.MovieService
import st.orm.demo.imdb.service.PersonGalleryService
import st.orm.demo.imdb.service.PersonService
import st.orm.demo.imdb.service.RecentlyAddedService
import st.orm.demo.imdb.service.RecommendationService
import st.orm.demo.imdb.service.SearchService
import st.orm.demo.imdb.service.InsightsService
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.ClubService
import st.orm.demo.imdb.service.InviteService
import st.orm.demo.imdb.service.StatisticsService
import st.orm.demo.imdb.service.TopMoviesService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.demo.imdb.service.StreamingService
import st.orm.demo.imdb.service.TmdbService
import st.orm.demo.imdb.service.WikipediaSummaryService

/**
 * Mounts the static resources, the server-rendered pages, and the REST routes.
 * Services come from Ktor's dependency container; the route functions
 * themselves stay DI-free and receive their services as parameters.
 */
fun Application.configureRouting() {
    val accountService: AccountService by dependencies
    val gitSignIn: GitSignIn by dependencies
    val freepodSignIn: FreepodSignIn by dependencies
    val homeService: HomeService by dependencies
    val topMoviesService: TopMoviesService by dependencies
    val watchlistService: WatchlistService by dependencies
    val movieSeenService: MovieSeenService by dependencies
    val catchUpService: CatchUpService by dependencies
    val statisticsService: StatisticsService by dependencies
    val insightsService: InsightsService by dependencies
    val friendService: FriendService by dependencies
    val clubService: ClubService by dependencies
    val inviteService: InviteService by dependencies
    val searchService: SearchService by dependencies
    val browseService: BrowseService by dependencies
    val recentlyAddedService: RecentlyAddedService by dependencies
    val recommendationService: RecommendationService by dependencies
    val movieService: MovieService by dependencies
    val personService: PersonService by dependencies
    val posterController: PosterController by dependencies
    val personGalleryService: PersonGalleryService by dependencies
    val wikipediaSummaryService: WikipediaSummaryService by dependencies
    val tmdbService: TmdbService by dependencies
    val streamingService: StreamingService by dependencies
    routing {
        // Static resources at the same URLs the templates reference.
        staticResources("/css", "static/css")
        staticResources("/js", "static/js")
        staticResources("/img", "static/img")

        accountRoutes(accountService, streamingService, gitSignIn, freepodSignIn)
        homeRoutes(homeService)
        topMoviesRoutes(topMoviesService)
        watchlistRoutes(watchlistService)
        movieSeenRoutes(movieSeenService, catchUpService, friendService, clubService)
        statisticsRoutes(statisticsService)
        insightsRoutes(insightsService, streamingService)
        friendsRoutes(friendService)
        clubsRoutes(clubService)
        inviteRoutes(inviteService)
        searchRoutes(searchService)
        browseRoutes(browseService)
        recentlyAddedRoutes(recentlyAddedService)
        forYouRoutes(recommendationService, streamingService)
        movieRoutes(movieService, watchlistService, movieSeenService)
        personRoutes(personService)
        posterRoutes(posterController)
        galleryRoutes(personGalleryService)
        summaryRoutes(wikipediaSummaryService)
        trailerRoutes(tmdbService)
        watchProviderRoutes(tmdbService, streamingService)
    }
}
