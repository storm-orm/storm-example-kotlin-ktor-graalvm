package st.orm.demo.imdb.web

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
import st.orm.demo.imdb.service.AccountService
import st.orm.demo.imdb.service.BrowseService
import st.orm.demo.imdb.service.CatchUpService
import st.orm.demo.imdb.service.CinemaService
import st.orm.demo.imdb.service.FreepodSignIn
import st.orm.demo.imdb.service.GitSignIn
import st.orm.demo.imdb.service.HomeService
import st.orm.demo.imdb.service.ListService
import st.orm.demo.imdb.service.MovieFilterService
import st.orm.demo.imdb.service.MovieNightService
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.MovieService
import st.orm.demo.imdb.service.PersonGalleryService
import st.orm.demo.imdb.service.PersonService
import st.orm.demo.imdb.service.QuizService
import st.orm.demo.imdb.service.PlayService
import st.orm.demo.imdb.service.HigherLowerService
import st.orm.demo.imdb.service.ChainService
import st.orm.demo.imdb.service.DraftService
import st.orm.demo.imdb.service.SwipeService
import st.orm.demo.imdb.service.ConnectService
import st.orm.demo.imdb.service.DailyPuzzleService
import st.orm.demo.imdb.service.RecentlyAddedService
import st.orm.demo.imdb.service.RecommendationService
import st.orm.demo.imdb.service.SearchService
import st.orm.demo.imdb.service.InsightsService
import st.orm.demo.imdb.service.FriendService
import st.orm.demo.imdb.service.ClubService
import st.orm.demo.imdb.service.CommunityService
import st.orm.demo.imdb.service.InviteService
import st.orm.demo.imdb.service.StatisticsService
import st.orm.demo.imdb.service.WatchlistService
import st.orm.demo.imdb.service.StreamingService
import st.orm.demo.imdb.service.TmdbService
import st.orm.demo.imdb.service.WikipediaSummaryService
import st.orm.demo.imdb.service.WrappedService

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
    val watchlistService: WatchlistService by dependencies
    val listService: ListService by dependencies
    val movieSeenService: MovieSeenService by dependencies
    val catchUpService: CatchUpService by dependencies
    val cinemaService: CinemaService by dependencies
    val statisticsService: StatisticsService by dependencies
    val insightsService: InsightsService by dependencies
    val wrappedService: WrappedService by dependencies
    val friendService: FriendService by dependencies
    val communityService: CommunityService by dependencies
    val clubService: ClubService by dependencies
    val movieNightService: MovieNightService by dependencies
    val inviteService: InviteService by dependencies
    val searchService: SearchService by dependencies
    val browseService: BrowseService by dependencies
    val recentlyAddedService: RecentlyAddedService by dependencies
    val recommendationService: RecommendationService by dependencies
    val movieService: MovieService by dependencies
    val personService: PersonService by dependencies
    val quizService: QuizService by dependencies
    val playService: PlayService by dependencies
    val higherLowerService: HigherLowerService by dependencies
    val chainService: ChainService by dependencies
    val draftService: DraftService by dependencies
    val swipeService: SwipeService by dependencies
    val connectService: ConnectService by dependencies
    val dailyPuzzleService: DailyPuzzleService by dependencies
    val posterController: PosterController by dependencies
    val personGalleryService: PersonGalleryService by dependencies
    val wikipediaSummaryService: WikipediaSummaryService by dependencies
    val tmdbService: TmdbService by dependencies
    val streamingService: StreamingService by dependencies
    val movieFilterService: MovieFilterService by dependencies
    routing {
        // Static resources at the same URLs the templates reference.
        staticResources("/css", "static/css")
        staticResources("/js", "static/js")
        staticResources("/img", "static/img")

        accountRoutes(accountService, streamingService, movieFilterService, gitSignIn, freepodSignIn)
        homeRoutes(homeService)
        cinemaRoutes(cinemaService)
        watchlistRoutes(watchlistService)
        listsRoutes(listService)
        movieSeenRoutes(movieSeenService, catchUpService, friendService, clubService)
        statisticsRoutes(statisticsService)
        insightsRoutes(insightsService, streamingService)
        wrappedRoutes(wrappedService)
        friendsRoutes(friendService)
        communityRoutes(communityService)
        clubsRoutes(clubService, listService, movieNightService, quizService, playService)
        movieNightRoutes(movieNightService, movieSeenService)
        inviteRoutes(inviteService)
        searchRoutes(searchService)
        browseRoutes(browseService)
        recentlyAddedRoutes(recentlyAddedService)
        forYouRoutes(recommendationService, streamingService)
        movieRoutes(movieService, watchlistService, movieSeenService)
        personRoutes(personService)
        quizRoutes(quizService)
        dailyPuzzleRoutes(dailyPuzzleService)
        playRoutes(playService, higherLowerService, chainService, draftService, swipeService, movieNightService, connectService)
        swipeRoutes(swipeService)
        posterRoutes(posterController)
        galleryRoutes(personGalleryService)
        summaryRoutes(wikipediaSummaryService)
        trailerRoutes(tmdbService)
        watchProviderRoutes(tmdbService, streamingService)
    }
}
