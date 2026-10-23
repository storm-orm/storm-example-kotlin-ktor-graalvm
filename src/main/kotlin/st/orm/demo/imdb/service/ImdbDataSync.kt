package st.orm.demo.imdb.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import st.orm.Entity
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieView
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.repository.GenreRepository
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.repository.EntityRepository
import st.orm.template.entityId
import st.orm.template.refById
import st.orm.template.transaction
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * Keeps the catalogue in step with the public IMDB dataset, which IMDB republishes daily.
 *
 * The catalogue holds every movie that has reached the configured number of votes, with its
 * genres, rating, cast and crew, and the people credited. A run reads the dataset once more and
 * brings those rows up to date: it inserts what is new, updates what changed, and removes the
 * genres and credits IMDB dropped. A movie stays once it is in, even when its votes fall below
 * the threshold, because watchlist entries and views refer to it; its data keeps being updated.
 * Raising the threshold therefore only stops new movies from joining, and lowering it adds the
 * movies that now qualify on the next run.
 *
 * Rows compare as values, since entities are immutable data classes, so a row equal to its
 * stored version is not written, and a daily run writes only the day's changes.
 *
 * The work is done in chunks, each in a transaction of its own: a chunk is parsed from the file
 * first, and its transaction only reads the stored rows and writes the difference. A run that
 * stops halfway keeps every chunk it finished, and the next run does the rest. On an empty
 * database the first run is the initial import.
 */
class ImdbDataSync(
    private val movieRepository: MovieRepository,
    private val genreRepository: GenreRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val personRepository: PersonRepository,
    private val principalRepository: PrincipalRepository,
    private val ratingRepository: RatingRepository,
    private val movieViewRepository: MovieViewRepository,
    private val movieAdditionRepository: MovieAdditionRepository,
    private val datasets: ImdbDatasets,
    private val objectMapper: ObjectMapper,
    private val minimumVoteCount: Int
) {

    private val logger = LoggerFactory.getLogger(ImdbDataSync::class.java)

    private data class RatingRow(val averageRating: BigDecimal, val voteCount: Int)

    /** A movie as the dataset describes it: the movie, its genre names and its rating, if any. */
    private data class Title(val movie: Movie, val genreNames: List<String>, val rating: RatingRow?)

    /** The moment the movies a run adds join the catalogue; absent for the initial import. */
    private data class Addition(val at: Instant)

    private data class Credit(
        val movieId: String,
        val ordering: Int,
        val personId: String,
        val category: String,
        val characters: String?
    )

    /** Whether the catalogue holds any movies, that is, whether the initial import has run. */
    fun isEmpty(): Boolean = !movieRepository.exists()

    suspend fun sync() = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        logger.info("Syncing the IMDB dataset (movies with at least {} votes)...", minimumVoteCount)
        // Fetched up front, so the whole run reads one edition of the dataset.
        val ratingsFile = datasets.fetch("title.ratings.tsv.gz")
        val titlesFile = datasets.fetch("title.basics.tsv.gz")
        val creditsFile = datasets.fetch("title.principals.tsv.gz")
        val namesFile = datasets.fetch("name.basics.tsv.gz")

        val catalogue = movieRepository.findAllRef().mapTo(HashSet()) { it.entityId() }
        val ratings = readRatings(ratingsFile, catalogue)
        val movieIds = syncMovies(titlesFile, catalogue, ratings)
        syncPersons(namesFile, readCreditedPersonIds(creditsFile, movieIds))
        syncCredits(creditsFile, movieIds)
        seedFeaturedMovie()

        logger.info("IMDB sync finished in {} seconds.", (System.currentTimeMillis() - startedAt) / 1000)
    }

    /** The ratings of the movies in the catalogue, and of every title with enough votes to join it. */
    private fun readRatings(file: Path, catalogue: Set<String>): Map<String, RatingRow> =
        file.readRows { lines ->
            buildMap {
                lines.forEach { line ->
                    val fields = line.split('\t')
                    val voteCount = fields[2].toInt()
                    if (voteCount >= minimumVoteCount || fields[0] in catalogue) {
                        put(fields[0], RatingRow(BigDecimal(fields[1]), voteCount))
                    }
                }
            }
        }

    /** Syncs the movies with their ratings and genres, and returns the ids of all movies in the catalogue. */
    private suspend fun syncMovies(
        file: Path,
        catalogue: Set<String>,
        ratings: Map<String, RatingRow>
    ): Set<String> {
        val genresByName = genreRepository.findAll().associateByTo(HashMap()) { it.name }
        val movieIds = HashSet<String>()
        var writeCount = 0
        // A sync of an empty catalogue is the initial import: its movies are the
        // catalogue, not additions to it. Any later run records the movies it adds,
        // as of one moment for the run.
        val addition = if (catalogue.isEmpty()) null else Addition(Instant.now())
        file.readRows { lines ->
            lines.mapNotNull { line -> parseTitle(line, catalogue, ratings) }
                .chunked(CHUNK_SIZE)
                .forEach { titles ->
                    writeCount += writeTitles(titles, genresByName, addition)
                    titles.mapTo(movieIds) { it.movie.id }
                }
        }
        logger.info("Synced {} movies: {} rows written.", movieIds.size, writeCount)
        return movieIds
    }

    /** A movie in the catalogue, or one with enough votes to join it; null for any other title. */
    private fun parseTitle(line: String, catalogue: Set<String>, ratings: Map<String, RatingRow>): Title? {
        val titleId = line.substringBefore('\t')
        if (titleId !in catalogue && titleId !in ratings) return null
        val fields = line.split('\t')
        if (fields[1] != "movie") return null
        return Title(
            movie = Movie(
                id = titleId,
                primaryTitle = fields[2],
                originalTitle = fields[3],
                startYear = fields[5].toIntOrNull(),
                runtimeMinutes = fields[7].toIntOrNull()
            ),
            genreNames = fields[8].takeUnless { it == NULL_VALUE }?.split(',').orEmpty(),
            rating = ratings[titleId]
        )
    }

    /**
     * Writes a chunk of titles: the movies, their ratings and their genres,
     * and, for a run that records its [addition]s, an addition for each movie
     * that was not stored yet.
     */
    private suspend fun writeTitles(titles: List<Title>, genresByName: MutableMap<String, Genre>, addition: Addition?): Int =
        transaction {
            val movies = titles.map { it.movie }
            val ratings = titles.mapNotNull { title ->
                title.rating?.let { Rating(title.movie, it.averageRating, it.voteCount) }
            }
            val genreLinks = titles.flatMap { title ->
                title.genreNames.map { name ->
                    MovieGenre(title.movie, genresByName.getOrPut(name) { genreRepository.insertAndFetch(Genre(name = name)) })
                }
            }
            val storedMovies = movieRepository.findAllById(movies.map { it.id })
            var writeCount = movieRepository.merge(storedMovies, movies)
            if (addition != null) {
                val storedIds = storedMovies.mapTo(HashSet()) { it.id }
                val added = movies.filter { it.id !in storedIds }
                movieAdditionRepository.insert(added.map { MovieAddition(it, addition.at) })
                writeCount += added.size
            }
            // Read after the movies are written: the stored ratings and links then hold the movies
            // as they are now, so a changed title does not make them compare as changed too.
            writeCount += ratingRepository.merge(ratingRepository.findAllById(movies), ratings)
            writeCount += movieGenreRepository.replace(movieGenreRepository.findByMovies(movies), genreLinks)
            writeCount
        }

    /**
     * The people credited in the catalogue's movies. The credits file is read twice, here and in
     * [syncCredits], because the people must be stored before the credits that refer to them, and
     * the names file lists them in an order of its own.
     */
    private fun readCreditedPersonIds(file: Path, movieIds: Set<String>): Set<String> =
        file.readRows { lines ->
            lines.filter { it.substringBefore('\t') in movieIds }
                .mapTo(HashSet()) { it.split('\t')[2] }
        }

    private suspend fun syncPersons(file: Path, personIds: Set<String>) {
        var writeCount = 0
        file.readRows { lines ->
            lines.filter { it.substringBefore('\t') in personIds }
                .map { line ->
                    val fields = line.split('\t')
                    Person(
                        id = fields[0],
                        primaryName = fields[1],
                        birthYear = fields[2].toIntOrNull(),
                        deathYear = fields[3].toIntOrNull()
                    )
                }
                .chunked(CHUNK_SIZE)
                .forEach { persons ->
                    writeCount += transaction {
                        personRepository.merge(personRepository.findAllById(persons.map { it.id }), persons)
                    }
                }
        }
        logger.info("Synced {} persons: {} rows written.", personIds.size, writeCount)
    }

    /**
     * Syncs the cast and crew. The credits file lists each movie's credits together, so a chunk
     * holds whole movies, and the credits a movie no longer has are removed with the chunk.
     */
    private suspend fun syncCredits(file: Path, movieIds: Set<String>) {
        var writeCount = 0
        file.readRows { lines ->
            lines.mapNotNull { line -> parseCredit(line, movieIds) }
                .groupConsecutiveBy { it.movieId }
                .chunked(CHUNK_SIZE)
                .forEach { movies -> writeCount += writeCredits(movies.flatten()) }
        }
        logger.info("Synced the cast and crew: {} rows written.", writeCount)
    }

    private fun parseCredit(line: String, movieIds: Set<String>): Credit? {
        val movieId = line.substringBefore('\t')
        if (movieId !in movieIds) return null
        val fields = line.split('\t')
        return Credit(
            movieId = movieId,
            ordering = fields[1].toInt(),
            personId = fields[2],
            category = fields[3],
            characters = parseCharacters(fields[5])
        )
    }

    private suspend fun writeCredits(credits: List<Credit>): Int = transaction {
        val personsById = personRepository.findAllById(credits.map { it.personId }.distinct()).associateBy { it.id }
        val principals = credits.mapNotNull { credit ->
            // A few credits name people missing from name.basics.
            personsById[credit.personId]?.let { person ->
                Principal(refById<Movie>(credit.movieId), credit.ordering, person, credit.category, credit.characters)
            }
        }
        val movies = credits.map { refById<Movie>(it.movieId) }.distinct()
        principalRepository.replace(principalRepository.findByMovies(movies), principals)
    }

    /**
     * Seeds a single view for The Matrix so the home page has a featured movie
     * after the initial import — a plain database seed, no special-case code.
     */
    private fun seedFeaturedMovie() {
        if (movieViewRepository.exists()) return
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

    companion object {
        private const val FEATURED_MOVIE_ID = "tt0133093" // The Matrix
        private const val NULL_VALUE = "\\N"
        /** Movies or people per transaction. */
        private const val CHUNK_SIZE = 1000
    }
}

private const val STREAM_BUFFER_SIZE = 64 * 1024

/** Streams the data lines of a gzipped dataset file, the header skipped. */
private inline fun <T> Path.readRows(block: (Sequence<String>) -> T): T =
    GZIPInputStream(Files.newInputStream(this), STREAM_BUFFER_SIZE)
        .bufferedReader()
        .use { reader -> block(reader.lineSequence().drop(1)) }

/** Groups runs of consecutive elements that share a key. */
private fun <T, K> Sequence<T>.groupConsecutiveBy(key: (T) -> K): Sequence<List<T>> = sequence {
    var group = ArrayList<T>()
    for (element in this@groupConsecutiveBy) {
        if (group.isNotEmpty() && key(group.first()) != key(element)) {
            yield(group)
            group = ArrayList()
        }
        group.add(element)
    }
    if (group.isNotEmpty()) yield(group)
}

/**
 * Brings the stored rows up to date with the desired ones: inserts the rows that are not
 * stored yet and updates those that differ from their stored version. Rows equal to their
 * stored version are left alone. Returns the number of rows written.
 */
private fun <E : Entity<ID>, ID : Any> EntityRepository<E, ID>.merge(stored: List<E>, desired: List<E>): Int {
    val storedById = stored.associateBy { it.id() }
    val (changed, added) = desired.filter { it != storedById[it.id()] }.partition { it.id() in storedById }
    insert(added)
    update(changed)
    return added.size + changed.size
}

/** Like [merge], and also removes the stored rows that are no longer desired. */
private fun <E : Entity<ID>, ID : Any> EntityRepository<E, ID>.replace(stored: List<E>, desired: List<E>): Int {
    val desiredIds = desired.mapTo(HashSet()) { it.id() }
    val dropped = stored.filter { it.id() !in desiredIds }
    remove(dropped)
    return dropped.size + merge(stored, desired)
}
