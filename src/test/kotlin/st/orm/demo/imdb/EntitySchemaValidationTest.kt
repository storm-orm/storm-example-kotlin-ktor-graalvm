package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieSummary
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.PersonGallery
import st.orm.demo.imdb.model.PersonSummary
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
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
@StormTest(database = TestDatabase.POSTGRESQL, scripts = ["/db/migration/V1__create_schema.sql", "/db/migration/V2__search_indexes.sql", "/db/migration/V3__search_index_statistics.sql", "/db/migration/V4__lead_credit_index.sql", "/db/migration/V5__accent_folding.sql", "/db/migration/V6__movie_folded_titles.sql", "/db/migration/V7__person_folded_name.sql", "/db/migration/V8__person_folded_name_index.sql", "/db/migration/V9__person_folded_name_statistics.sql", "/db/migration/V10__movie_title_expression_indexes.sql", "/db/migration/V11__person_name_expression_index.sql", "/db/migration/V12__person_name_expression_statistics.sql", "/db/migration/V13__drop_folded_columns.sql"])
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
            MovieView::class,
            Watchlist::class,
            PersonGallery::class,
            MovieSummary::class,
            PersonSummary::class
        )
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
