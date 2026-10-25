package st.orm.demo.imdb

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ImdbSourceRepository
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
import st.orm.demo.imdb.repository.RecommendationEventRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.TmdbSourceRepository
import st.orm.demo.imdb.repository.WatchProviderRepository
import st.orm.demo.imdb.repository.WikidataSourceRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.demo.imdb.service.MovieSeenService
import st.orm.demo.imdb.service.MovieSourceService
import st.orm.demo.imdb.service.RecommendationService
import st.orm.demo.imdb.service.TmdbClient
import st.orm.demo.imdb.service.TmdbService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.SqlCapture
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

/** Prints every captured statement so the test output shows the generated SQL. */
fun SqlCapture.printStatements(label: String) {
    statements().forEach { captured ->
        println("[$label] ${captured.operation()}: ${captured.statement().replace('\n', ' ')}")
    }
}

/** An account with a row, as the first save stores it; anonymous unless given a name. */
fun ORMTemplate.insertUser(id: String, name: String? = null): User {
    val now = Instant.parse("2026-07-01T09:00:00Z")
    return repository<UserRepository>().insertAndFetch(User(id = id, name = name, avatarUrl = null, createdAt = now, lastSeenAt = now))
}

/** The seen list's service on the test database, with TMDB off unless a client is given. */
fun movieSeenService(orm: ORMTemplate, tmdbClient: TmdbClient = TmdbClient(null, jacksonObjectMapper())) = MovieSeenService(
    orm.repository<MovieRepository>(),
    orm.repository<UserRepository>(),
    orm.repository<WatchlistRepository>(),
    orm.repository<MovieSeenRepository>(),
    orm.repository<CheckInRepository>(),
    RecommendationService(
        orm.repository<UserRepository>(),
        orm.repository<MovieRepository>(),
        orm.repository<MovieSeenRepository>(),
        orm.repository<WatchlistRepository>(),
        orm.repository<RecommendationDismissalRepository>(),
        orm.repository<MovieAdditionRepository>(),
        orm.repository<MovieGenreRepository>(),
        orm.repository<PrincipalRepository>(),
        orm.repository<RatingRepository>(),
        orm.repository<MovieViewRepository>(),
        orm.repository<RecommendationRepository>(),
        orm.repository<RecommendationEventRepository>(),
        orm.repository<CheckInRepository>()
    ),
    MovieSourceService(orm.repository<ImdbSourceRepository>(), orm.repository<WikidataSourceRepository>(), tmdbService(orm, tmdbClient))
)

fun tmdbService(orm: ORMTemplate, tmdbClient: TmdbClient) =
    TmdbService(orm.repository<MovieRepository>(), orm.repository<TmdbSourceRepository>(), orm.repository<WatchProviderRepository>(), tmdbClient)
