package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.service.ImdbDataSync
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.refById
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import st.orm.test.kotlin.recording
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * Runs the IMDB sync over two editions of a miniature dataset: the first run on the
 * empty database is the initial import, the second applies a day's changes, and a
 * third over the same edition finds nothing to write.
 */
@StormTest(scripts = ["/schema.sql"])
class ImdbDataSyncTest {

    @TempDir
    lateinit var datasetDirectory: Path

    @Test
    fun `the first sync imports the movies with enough votes`(orm: ORMTemplate) {
        val sync = imdbDataSync(orm)
        writeFirstEdition()

        runBlocking { sync.sync() }

        // The movie below the threshold and the series stay out.
        assertEquals(listOf("tt0000001"), orm.repository<MovieRepository>().findAll().map { it.id })
        assertEquals(2, orm.repository<MovieGenreRepository>().count())
        // Only the credited people are imported.
        assertEquals(listOf("nm0000001", "nm0000002"), orm.repository<PersonRepository>().findAll().map { it.id }.sorted())
        assertEquals(2, orm.repository<PrincipalRepository>().count())
        // The initial import is no addition: the catalogue started with its movies.
        assertEquals(0, orm.repository<MovieAdditionRepository>().count())
    }

    @Test
    fun `a later sync applies the changes and keeps movies that dropped below the threshold`(orm: ORMTemplate) {
        val sync = imdbDataSync(orm)
        writeFirstEdition()
        runBlocking { sync.sync() }
        writeSecondEdition()

        runBlocking { sync.sync() }

        val movies = orm.repository<MovieRepository>().findAll().associateBy { it.id }
        assertEquals(setOf("tt0000001", "tt0000002"), movies.keys)
        assertEquals("First (Restored)", movies.getValue("tt0000001").primaryTitle)
        val rating = orm.repository<RatingRepository>().getById(movies.getValue("tt0000001"))
        assertEquals(BigDecimal("7.6"), rating.averageRating)
        assertEquals(900, rating.voteCount)
        assertEquals(listOf("Drama"), orm.repository<MovieGenreRepository>().findGenres(movies.getValue("tt0000001")).map { it.name })
        val cast = orm.repository<PrincipalRepository>().findByMovies(listOf(refById<Movie>("tt0000001")))
        assertEquals(listOf(1 to "nm0000001", 3 to "nm0000003"), cast.map { it.ordering to it.person.id }.sortedBy { it.first })
        // The movie that joined is recorded as an addition; the renamed one is not.
        assertEquals(listOf("tt0000002"), orm.repository<MovieAdditionRepository>().findAll().map { it.movie.id })
    }

    @Test
    fun `a sync over an unchanged edition writes nothing`(orm: ORMTemplate, capture: SqlCapture) {
        val sync = imdbDataSync(orm)
        writeSecondEdition()
        runBlocking { sync.sync() }

        runBlocking { capture.recording { sync.sync() } }

        capture.printStatements("unchanged")
        assertEquals(0, capture.count(Operation.INSERT))
        assertEquals(0, capture.count(Operation.UPDATE))
        assertEquals(0, capture.count(Operation.DELETE))
    }

    private fun imdbDataSync(orm: ORMTemplate) = ImdbDataSync(
        orm.repository<MovieRepository>(),
        orm.repository<GenreRepository>(),
        orm.repository<MovieGenreRepository>(),
        orm.repository<PersonRepository>(),
        orm.repository<PrincipalRepository>(),
        orm.repository<RatingRepository>(),
        orm.repository<MovieAdditionRepository>(),
        datasets = { fileName -> datasetDirectory.resolve(fileName) },
        objectMapper = jacksonObjectMapper(),
        minimumVoteCount = 1000
    )

    private fun writeFirstEdition() {
        writeDataset(
            "title.ratings.tsv.gz", "tconst\taverageRating\tnumVotes",
            "tt0000001\t7.5\t2000",
            "tt0000002\t6.1\t500",
            "tt0000003\t8.0\t5000"
        )
        writeDataset(
            "title.basics.tsv.gz", TITLE_HEADER,
            "tt0000001\tmovie\tFirst\tFirst\t0\t1999\t\\N\t120\tComedy,Drama",
            "tt0000002\tmovie\tSecond\tSecond\t0\t2001\t\\N\t95\tThriller",
            "tt0000003\ttvSeries\tThird\tThird\t0\t2005\t2008\t45\tDrama"
        )
        writeDataset(
            "title.principals.tsv.gz", PRINCIPAL_HEADER,
            "tt0000001\t1\tnm0000001\tactor\t\\N\t[\"Hero\"]",
            "tt0000001\t2\tnm0000002\tdirector\t\\N\t\\N",
            "tt0000003\t1\tnm0000003\tactress\t\\N\t[\"Lead\"]"
        )
        writeDataset(
            "name.basics.tsv.gz", NAME_HEADER,
            "nm0000001\tAlice Able\t1970\t\\N",
            "nm0000002\tBob Baker\t1965\t2020",
            "nm0000003\tCarol Cole\t1980\t\\N"
        )
    }

    /**
     * The next day: the first movie is renamed, rerated below the threshold, loses a genre,
     * a director and gains an actress; the second movie has collected enough votes.
     */
    private fun writeSecondEdition() {
        writeDataset(
            "title.ratings.tsv.gz", "tconst\taverageRating\tnumVotes",
            "tt0000001\t7.6\t900",
            "tt0000002\t6.3\t1500",
            "tt0000003\t8.0\t5100"
        )
        writeDataset(
            "title.basics.tsv.gz", TITLE_HEADER,
            "tt0000001\tmovie\tFirst (Restored)\tFirst\t0\t1999\t\\N\t120\tDrama",
            "tt0000002\tmovie\tSecond\tSecond\t0\t2001\t\\N\t95\tThriller",
            "tt0000003\ttvSeries\tThird\tThird\t0\t2005\t2008\t45\tDrama"
        )
        writeDataset(
            "title.principals.tsv.gz", PRINCIPAL_HEADER,
            "tt0000001\t1\tnm0000001\tactor\t\\N\t[\"Hero\"]",
            "tt0000001\t3\tnm0000003\tactress\t\\N\t[\"Sidekick\"]",
            "tt0000002\t1\tnm0000002\tdirector\t\\N\t\\N",
            "tt0000003\t1\tnm0000003\tactress\t\\N\t[\"Lead\"]"
        )
        writeDataset(
            "name.basics.tsv.gz", NAME_HEADER,
            "nm0000001\tAlice Able\t1970\t\\N",
            "nm0000002\tBob Baker\t1965\t2020",
            "nm0000003\tCarol Cole\t1980\t\\N"
        )
    }

    private fun writeDataset(fileName: String, header: String, vararg rows: String) {
        GZIPOutputStream(Files.newOutputStream(datasetDirectory.resolve(fileName))).bufferedWriter().use { writer ->
            (listOf(header) + rows).forEach { writer.appendLine(it) }
        }
    }

    companion object {
        private const val TITLE_HEADER =
            "tconst\ttitleType\tprimaryTitle\toriginalTitle\tisAdult\tstartYear\tendYear\truntimeMinutes\tgenres"
        private const val PRINCIPAL_HEADER = "tconst\tordering\tnconst\tcategory\tjob\tcharacters"
        private const val NAME_HEADER = "nconst\tprimaryName\tbirthYear\tdeathYear"
    }
}
