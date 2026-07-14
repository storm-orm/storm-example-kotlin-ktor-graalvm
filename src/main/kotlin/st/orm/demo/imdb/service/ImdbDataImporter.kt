package st.orm.demo.imdb.service

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.template.transaction
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * Imports the public IMDB dataset on first startup: movies with at least the
 * configured number of votes, their genres, cast and crew, and ratings.
 *
 * The write path is Flow-based: TSV rows are parsed into entities and handed
 * to Storm's suspending batch insert, which writes fixed-size JDBC batches
 * while the file is still streaming — one pass per file, no materialized
 * entity lists. Dataset files are downloaded once and cached locally; the
 * import is skipped entirely when movie data is already present.
 */
class ImdbDataImporter(
    private val movieRepository: MovieRepository,
    private val genreRepository: GenreRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val personRepository: PersonRepository,
    private val principalRepository: PrincipalRepository,
    private val ratingRepository: RatingRepository,
    private val movieViewRepository: MovieViewRepository,
    private val objectMapper: ObjectMapper,
    private val properties: ImdbImportProperties
) {

    private val logger = LoggerFactory.getLogger(ImdbDataImporter::class.java)

    private data class RatingRow(val averageRating: BigDecimal, val voteCount: Int)

    private data class PrincipalRow(
        val movieId: String,
        val ordering: Int,
        val personId: String,
        val category: String,
        val characters: String?
    )

    /**
     * Runs the import during application startup, blocking until it finishes.
     * The Ktor equivalent of Spring's ApplicationRunner: it is invoked once
     * from Application.module() after Flyway and the Storm plugin are ready.
     */
    fun import() {
        if (movieRepository.count() > 0) {
            logger.info("Movie data already present, skipping IMDB import.")
            return
        }
        val startedAt = System.currentTimeMillis()
        logger.info("Importing IMDB dataset (movies with at least {} votes)...", properties.minimumVoteCount)

        runBlocking {
            // One explicit, coroutine-native Storm transaction around the
            // whole import: a failure rolls everything back, so a restart
            // always begins from a clean database.
            transaction {
                val qualifyingRatings = readQualifyingRatings()
                val (moviesById, genreNamesByMovieId) = importMovies(qualifyingRatings.keys)
                importGenres(moviesById, genreNamesByMovieId)
                val principalRows = readPrincipalRows(moviesById.keys)
                val personsById = importPersons(principalRows)
                importPrincipals(principalRows, moviesById, personsById)
                importRatings(moviesById, qualifyingRatings)
                seedFeaturedMovie()
            }
        }

        logger.info("IMDB import finished in {} seconds.", (System.currentTimeMillis() - startedAt) / 1000)
    }

    /** Ratings of all titles (any type) that meet the vote threshold. */
    private fun readQualifyingRatings(): Map<String, RatingRow> =
        streamDataset("title.ratings.tsv.gz") { lines ->
            buildMap {
                lines.forEach { line ->
                    val fields = line.split('\t')
                    val voteCount = fields[2].toInt()
                    if (voteCount >= properties.minimumVoteCount) {
                        put(fields[0], RatingRow(BigDecimal(fields[1]), voteCount))
                    }
                }
            }
        }.also { logger.info("Found {} titles with at least {} votes.", it.size, properties.minimumVoteCount) }

    /**
     * Imports qualifying movies in a single streaming pass: each parsed row
     * is emitted into the batched insert while the file streams, and is
     * remembered in the id-to-movie map for the later import steps.
     */
    private suspend fun importMovies(
        qualifyingTitleIds: Set<String>
    ): Pair<Map<String, Movie>, Map<String, List<String>>> =
        streamDataset("title.basics.tsv.gz") { lines ->
            val moviesById = LinkedHashMap<String, Movie>()
            val genreNamesByMovieId = HashMap<String, List<String>>()
            val movies = lines.asFlow().mapNotNull { line ->
                val titleId = line.substringBefore('\t')
                if (titleId !in qualifyingTitleIds) return@mapNotNull null
                val fields = line.split('\t')
                if (fields[1] != "movie") return@mapNotNull null
                fields[8].takeUnless { it == NULL_VALUE }?.let { genreList ->
                    genreNamesByMovieId[titleId] = genreList.split(',')
                }
                Movie(
                    id = titleId,
                    primaryTitle = fields[2],
                    originalTitle = fields[3],
                    startYear = fields[5].toIntOrNull(),
                    runtimeMinutes = fields[7].toIntOrNull()
                ).also { movie -> moviesById[movie.id] = movie }
            }
            movieRepository.insert(movies, INSERT_BATCH_SIZE)
            logger.info("Imported {} movies.", moviesById.size)
            moviesById to genreNamesByMovieId
        }

    /** Inserts the distinct genres and the movie-genre junction rows. */
    private suspend fun importGenres(moviesById: Map<String, Movie>, genreNamesByMovieId: Map<String, List<String>>) {
        val genreNames = genreNamesByMovieId.values.flatten().toSortedSet()
        val genresByName = genreRepository
            .insertAndFetch(genreNames.map { Genre(name = it) })
            .associateBy { it.name }
        var linkCount = 0
        val movieGenres = flow {
            genreNamesByMovieId.forEach { (movieId, names) ->
                val movie = moviesById.getValue(movieId)
                names.forEach { name ->
                    emit(MovieGenre(movie, genresByName.getValue(name)))
                    linkCount++
                }
            }
        }
        movieGenreRepository.insert(movieGenres, INSERT_BATCH_SIZE)
        logger.info("Imported {} genres and {} movie-genre links.", genresByName.size, linkCount)
    }

    /**
     * Collects the cast and crew rows of the imported movies. Filtering on
     * the actually imported movie ids (not the full qualifying set) keeps
     * credits of non-movie titles out, which would violate FK constraints.
     * The rows are needed twice (person filtering, then the actual insert),
     * so this is the one place the import materializes a list.
     */
    private fun readPrincipalRows(importedMovieIds: Set<String>): List<PrincipalRow> =
        streamDataset("title.principals.tsv.gz") { lines ->
            val rows = ArrayList<PrincipalRow>()
            lines.forEach { line ->
                val movieId = line.substringBefore('\t')
                if (movieId !in importedMovieIds) return@forEach
                val fields = line.split('\t')
                rows.add(
                    PrincipalRow(
                        movieId = movieId,
                        ordering = fields[1].toInt(),
                        personId = fields[2],
                        category = fields[3],
                        characters = parseCharacters(fields[5])
                    )
                )
            }
            logger.info("Collected {} cast and crew credits.", rows.size)
            rows
        }

    /** Imports every person referenced by the collected credits, streaming. */
    private suspend fun importPersons(principalRows: List<PrincipalRow>): Map<String, Person> {
        val referencedPersonIds = principalRows.mapTo(HashSet()) { it.personId }
        return streamDataset("name.basics.tsv.gz") { lines ->
            val personsById = HashMap<String, Person>(referencedPersonIds.size * 2)
            val persons = lines.asFlow().mapNotNull { line ->
                val personId = line.substringBefore('\t')
                if (personId !in referencedPersonIds) return@mapNotNull null
                val fields = line.split('\t')
                Person(
                    id = personId,
                    primaryName = fields[1],
                    birthYear = fields[2].toIntOrNull(),
                    deathYear = fields[3].toIntOrNull()
                ).also { person -> personsById[person.id] = person }
            }
            personRepository.insert(persons, INSERT_BATCH_SIZE)
            logger.info("Imported {} persons.", personsById.size)
            personsById
        }
    }

    private suspend fun importPrincipals(
        principalRows: List<PrincipalRow>,
        moviesById: Map<String, Movie>,
        personsById: Map<String, Person>
    ) {
        var importedCount = 0
        val principals = principalRows.asFlow().mapNotNull { row ->
            // A few credits reference persons missing from name.basics.
            val person = personsById[row.personId] ?: return@mapNotNull null
            importedCount++
            Principal(
                movie = moviesById.getValue(row.movieId),
                ordering = row.ordering,
                person = person,
                category = row.category,
                characters = row.characters
            )
        }
        principalRepository.insert(principals, INSERT_BATCH_SIZE)
        logger.info("Imported {} principals.", importedCount)
    }

    private suspend fun importRatings(moviesById: Map<String, Movie>, qualifyingRatings: Map<String, RatingRow>) {
        val ratings = moviesById.values.asFlow().map { movie ->
            val ratingRow = qualifyingRatings.getValue(movie.id)
            Rating(movie = movie, averageRating = ratingRow.averageRating, voteCount = ratingRow.voteCount)
        }
        ratingRepository.insert(ratings, INSERT_BATCH_SIZE)
        logger.info("Imported {} ratings.", moviesById.size)
    }

    /**
     * Seeds a single view for The Matrix so the home page has a featured
     * movie on first launch — a plain database seed, no special-case code.
     */
    private fun seedFeaturedMovie() {
        val theMatrix = movieRepository.findById(FEATURED_MOVIE_ID) ?: return
        movieViewRepository.insert(MovieView(movie = theMatrix, viewedAt = Instant.now()))
        logger.info("Seeded an initial view for '{}' as the featured movie.", theMatrix.primaryTitle)
    }

    /** The characters column holds a JSON array, e.g. ["Neo"]. */
    private fun parseCharacters(rawValue: String): String? {
        if (rawValue == NULL_VALUE || rawValue.isBlank()) return null
        return try {
            objectMapper.readValue<List<String>>(rawValue).joinToString(", ")
        } catch (ignored: JacksonException) {
            rawValue
        }
    }

    /** Streams the data lines (header skipped) of a cached dataset file. */
    private inline fun <T> streamDataset(fileName: String, block: (Sequence<String>) -> T): T =
        GZIPInputStream(Files.newInputStream(datasetFile(fileName)), STREAM_BUFFER_SIZE)
            .bufferedReader()
            .use { reader -> block(reader.lineSequence().drop(1)) }

    /** Returns the cached dataset file, downloading it first if needed. */
    private fun datasetFile(fileName: String): Path {
        Files.createDirectories(properties.cacheDirectory)
        val file = properties.cacheDirectory.resolve(fileName)
        if (Files.notExists(file)) {
            val uri = URI.create("${properties.datasetBaseUrl}/$fileName")
            logger.info("Downloading {}...", uri)
            val temporaryFile = Files.createTempFile(properties.cacheDirectory, fileName, ".download")
            try {
                val response = httpClient.send(
                    HttpRequest.newBuilder(uri).build(),
                    HttpResponse.BodyHandlers.ofFile(temporaryFile)
                )
                check(response.statusCode() == 200) { "Download of $uri failed with status ${response.statusCode()}" }
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING)
                logger.info("Downloaded {} ({} MB).", fileName, Files.size(file) / (1024 * 1024))
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        }
        return file
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    companion object {
        private const val FEATURED_MOVIE_ID = "tt0133093" // The Matrix
        private const val NULL_VALUE = "\\N"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
        private const val INSERT_BATCH_SIZE = 1000
    }
}
