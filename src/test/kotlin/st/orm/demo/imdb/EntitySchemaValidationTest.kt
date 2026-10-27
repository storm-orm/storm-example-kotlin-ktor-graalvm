package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.ChainLink
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ConnectPuzzle
import st.orm.demo.imdb.model.DailyPlay
import st.orm.demo.imdb.model.DailyPuzzle
import st.orm.demo.imdb.model.DraftPick
import st.orm.demo.imdb.model.DraftTheme
import st.orm.demo.imdb.model.Swipe
import st.orm.demo.imdb.model.SwipeRoundCard
import st.orm.demo.imdb.model.SwipeRoundSwipe
import st.orm.demo.imdb.model.GameCard
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.FriendRequest
import st.orm.demo.imdb.model.MovieSource
import st.orm.demo.imdb.model.Invite
import st.orm.demo.imdb.model.Friendship
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.model.MovieCountry
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.MovieList
import st.orm.demo.imdb.model.MovieListItem
import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNightCandidate
import st.orm.demo.imdb.model.MovieNightProvider
import st.orm.demo.imdb.model.MovieNightVote
import st.orm.demo.imdb.model.MovieTag
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.MovieViewDay
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.PersonGallery
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.QuizDay
import st.orm.demo.imdb.model.QuizPlay
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.model.RecommendationDismissal
import st.orm.demo.imdb.model.RecommendationEvent
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserLogin
import st.orm.demo.imdb.model.Watchlist
import st.orm.demo.imdb.model.YearShare
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import st.orm.test.TestDatabase

/**
 * Validates every entity against the database schema at the JDBC level:
 * column presence, type compatibility, nullability, primary keys, and
 * foreign key consistency. Unlike the other tests, which run on H2, this
 * one runs on PostgreSQL in a Testcontainers-managed container and applies
 * the Flyway migrations themselves, so the entities are checked against the
 * schema the application deploys with, on the dialect it deploys on. The
 * container starts once per test run; the class receives a database of its
 * own inside it.
 */
@StormTest(database = TestDatabase.POSTGRESQL, scripts = ["/db/migration/V1__create_schema.sql", "/db/migration/V2__store_folded_titles.sql", "/db/migration/V3__record_movie_additions.sql", "/db/migration/V4__personal_accounts.sql", "/db/migration/V5__recommendations.sql", "/db/migration/V6__recommendation_fatigue.sql", "/db/migration/V7__fold_old_views.sql", "/db/migration/V8__movie_view_day_natural_key.sql", "/db/migration/V9__personal_recently_viewed.sql", "/db/migration/V10__friends.sql", "/db/migration/V11__public_friend_invites.sql", "/db/migration/V12__seen_history.sql", "/db/migration/V13__check_ins.sql", "/db/migration/V14__clubs.sql", "/db/migration/V15__friend_requests.sql", "/db/migration/V16__movie_sources.sql", "/db/migration/V17__watch_providers.sql", "/db/migration/V18__refetch_watch_providers.sql", "/db/migration/V19__user_watch_providers.sql", "/db/migration/V20__movie_tags.sql", "/db/migration/V21__languages_from_tmdb.sql", "/db/migration/V22__ask_languages_again.sql", "/db/migration/V23__invite_accepted_count.sql", "/db/migration/V24__movie_countries.sql", "/db/migration/V25__ask_countries.sql", "/db/migration/V26__year_shares.sql", "/db/migration/V27__movie_lists.sql", "/db/migration/V28__movie_nights.sql", "/db/migration/V29__quiz.sql", "/db/migration/V30__games.sql", "/db/migration/V31__quiz_per_taste.sql", "/db/migration/V32__movie_night_votes.sql", "/db/migration/V33__movie_draft.sql", "/db/migration/V34__game_hints.sql", "/db/migration/V35__swipes.sql", "/db/migration/V36__connect.sql", "/db/migration/V37__daily_puzzles.sql", "/db/migration/V38__people_puzzles.sql", "/db/migration/V39__closed_games.sql", "/db/migration/V40__name_that_cast.sql", "/db/migration/V41__levels.sql", "/db/migration/V42__match_pools.sql", "/db/migration/V43__night_swipe.sql", "/db/migration/V44__badges.sql", "/db/migration/V45__closed_game_status.sql", "/db/migration/V46__reel_call.sql", "/db/migration/V47__goat.sql", "/db/migration/V48__levels_by_the_rites.sql", "/db/migration/V49__levels_by_the_movies_and_the_rites.sql", "/db/migration/V50__invite_acceptances.sql", "/db/migration/V51__swipe_decision_time.sql"])
class EntitySchemaValidationTest {

    @Test
    fun `entities match the database schema`(orm: ORMTemplate) {
        val errors = orm.validateSchema(
            Movie::class,
            Genre::class,
            MovieGenre::class,
            Person::class,
            Principal::class,
            Rating::class,
            MovieAddition::class,
            MovieView::class,
            MovieViewDay::class,
            User::class,
            UserLogin::class,
            Watchlist::class,
            MovieSeen::class,
            Recommendation::class,
            RecommendationEvent::class,
            RecommendationDismissal::class,
            PersonGallery::class,
            Friendship::class,
            CheckIn::class,
            Club::class,
            ClubMember::class,
            FriendRequest::class,
            MovieTag::class,
            MovieLanguage::class,
            MovieCountry::class,
            YearShare::class,
            MovieList::class,
            MovieListItem::class,
            MovieNight::class,
            MovieNightProvider::class,
            MovieNightCandidate::class,
            MovieNightVote::class,
            QuizDay::class,
            QuizPlay::class,
            GameMatch::class,
            GamePlayer::class,
            GameCard::class,
            ChainLink::class,
            DraftTheme::class,
            DraftPick::class,
            Swipe::class,
            SwipeRoundCard::class,
            SwipeRoundSwipe::class,
            ConnectPuzzle::class,
            DailyPuzzle::class,
            DailyPlay::class
        )
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }

    @Test
    fun `the sealed entities match their tables`(orm: ORMTemplate) {
        val errors = orm.validateSchema(Invite::class, MovieSource::class)
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
