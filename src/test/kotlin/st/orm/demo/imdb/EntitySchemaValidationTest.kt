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

/**
 * Validates every entity against the database schema at the JDBC level:
 * column presence, type compatibility, nullability, primary keys, and
 * foreign key consistency. The schema.sql script is the same DDL that
 * Flyway applies in production.
 */
@StormTest(scripts = ["/schema.sql"])
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
