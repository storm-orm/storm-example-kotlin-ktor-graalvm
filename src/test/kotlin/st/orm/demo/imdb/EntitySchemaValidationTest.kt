package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.Club
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
import st.orm.demo.imdb.model.MovieTag
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.MovieViewDay
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.PersonGallery
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.model.RecommendationDismissal
import st.orm.demo.imdb.model.RecommendationEvent
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.UserLogin
import st.orm.demo.imdb.model.Watchlist
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
@StormTest(database = TestDatabase.POSTGRESQL, scripts = ["/db/migration/V1__create_schema.sql", "/db/migration/V2__store_folded_titles.sql", "/db/migration/V3__record_movie_additions.sql", "/db/migration/V4__personal_accounts.sql", "/db/migration/V5__recommendations.sql", "/db/migration/V6__recommendation_fatigue.sql", "/db/migration/V7__fold_old_views.sql", "/db/migration/V8__movie_view_day_natural_key.sql", "/db/migration/V9__personal_recently_viewed.sql", "/db/migration/V10__friends.sql", "/db/migration/V11__public_friend_invites.sql", "/db/migration/V12__seen_history.sql", "/db/migration/V13__check_ins.sql", "/db/migration/V14__clubs.sql", "/db/migration/V15__friend_requests.sql", "/db/migration/V16__movie_sources.sql", "/db/migration/V17__watch_providers.sql", "/db/migration/V18__refetch_watch_providers.sql", "/db/migration/V19__user_watch_providers.sql", "/db/migration/V20__movie_tags.sql", "/db/migration/V21__languages_from_tmdb.sql", "/db/migration/V22__ask_languages_again.sql", "/db/migration/V23__invite_accepted_count.sql", "/db/migration/V24__movie_countries.sql"])
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
            MovieCountry::class
        )
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }

    @Test
    fun `the sealed entities match their tables`(orm: ORMTemplate) {
        val errors = orm.validateSchema(Invite::class, MovieSource::class)
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
