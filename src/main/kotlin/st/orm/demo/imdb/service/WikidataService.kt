package st.orm.demo.imdb.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieCountry
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.MovieTag
import st.orm.demo.imdb.model.Tag
import st.orm.demo.imdb.model.WikidataSource
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieTagRepository
import st.orm.demo.imdb.repository.WikidataSourceRepository
import st.orm.template.ref
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant

/**
 * Asks Wikidata where the movies were made and in what language, and turns
 * the answers into each movie's languages and tags. Every movie is asked in
 * the background, the new ones within a day of joining the catalogue, and
 * each again after [ASK_AGAIN_AFTER], as Wikidata grows. Wikidata names no
 * language for some movies; for those TMDB is asked the language the movie
 * was made in. TMDB's terms let what it answers be kept six months, and
 * asking again replaces it, so it never stays longer.
 */
class WikidataService(
    private val wikidataClient: WikidataClient,
    private val tmdbClient: TmdbClient,
    private val movieRepository: MovieRepository,
    private val wikidataSourceRepository: WikidataSourceRepository,
    private val movieLanguageRepository: MovieLanguageRepository,
    private val movieCountryRepository: MovieCountryRepository,
    private val movieTagRepository: MovieTagRepository
) {
    private val logger = LoggerFactory.getLogger(WikidataService::class.java)

    /**
     * Asks Wikidata about the movies it was not asked about lately, in
     * windows over the key: each window is one query to Wikidata, made while
     * the database connection is free, and its answers are written in one
     * transaction, so a run that stops halfway keeps what it asked. A pause
     * between the queries keeps the run polite to a shared service.
     */
    suspend fun askAboutMovies(now: Instant = Instant.now()) {
        var asked = 0
        var withoutLanguage = 0
        var namedByTmdb = 0
        movieRepository.findNotAskedOfWikidataSince(now.minus(ASK_AGAIN_AFTER), WINDOW_SIZE).collect { window ->
            val movies = window.content()
            val answers = withContext(Dispatchers.IO) { wikidataClient.findMovies(movies.map { it.id }) }
            val languages = languagesOf(movies, answers)
            transaction { store(movies, answers, languages, now) }
            val gaps = movies.filter { answers[it.id]?.languages.isNullOrEmpty() }
            asked += movies.size
            withoutLanguage += gaps.size
            namedByTmdb += gaps.count { languages.getValue(it.id).isNotEmpty() }
            delay(PAUSE.toMillis())
        }
        if (asked > 0) {
            logger.info("Asked Wikidata about {} movies; it named no language for {}, of which TMDB named {}.", asked, withoutLanguage, namedByTmdb)
        }
    }

    /**
     * The languages of a window of movies, by movie id: Wikidata's, or else
     * the one TMDB names, asked a few at a time, for the movies Wikidata
     * names none for. Without a TMDB key the gaps stay.
     */
    private suspend fun languagesOf(movies: List<Movie>, answers: Map<String, WikidataMovie>): Map<String, Set<String>> {
        val missing = movies.filter { answers[it.id]?.languages.isNullOrEmpty() }.takeIf { tmdbClient.enabled }.orEmpty()
        val calls = Semaphore(TMDB_PARALLEL_CALLS)
        val fromTmdb = coroutineScope {
            missing.map { movie ->
                async(Dispatchers.IO) { calls.withPermit { tmdbClient.findOriginalLanguage(movie.id) }?.let { movie.id to it } }
            }.awaitAll().filterNotNull().toMap()
        }
        return movies.associate { movie -> movie.id to (answers[movie.id]?.languages.orEmpty().ifEmpty { setOfNotNull(fromTmdb[movie.id]) }) }
    }

    /** The answers for a window of movies: the sources, and in place of what they had, the languages, the countries and the tags. */
    private fun store(movies: List<Movie>, answers: Map<String, WikidataMovie>, languages: Map<String, Set<String>>, now: Instant) {
        val refs = movies.map { it.ref() }
        wikidataSourceRepository.replace(movies.map { WikidataSource(movie = it.ref(), externalId = answers[it.id]?.itemId, fetchedAt = now) })
        movieLanguageRepository.replace(refs, movies.flatMap { movie ->
            languages.getValue(movie.id).map { MovieLanguage(movie.ref(), it) }
        })
        movieCountryRepository.replace(refs, movies.flatMap { movie ->
            answers[movie.id]?.countries.orEmpty().map { MovieCountry(movie.ref(), it) }
        })
        movieTagRepository.replace(refs, movies.flatMap { movie ->
            answers[movie.id]?.let { tagsOf(it.countries, languages.getValue(movie.id)) }.orEmpty().map { MovieTag(movie.ref(), it) }
        })
    }

    companion object {
        /** The movies one query to Wikidata asks about. */
        private const val WINDOW_SIZE = 200
        private val PAUSE: Duration = Duration.ofSeconds(1)
        /** Within TMDB's six months, with room for a missed day. */
        private val ASK_AGAIN_AFTER: Duration = Duration.ofDays(170)
        private const val TMDB_PARALLEL_CALLS = 6

        /**
         * The tags of a movie, by the countries Wikidata says it was made in
         * and its languages: each tag has its rule here, which the compiler asks for.
         */
        fun tagsOf(countries: Set<String>, languages: Set<String>): List<Tag> = Tag.entries.filter { tag ->
            when (tag) {
                Tag.HOLLYWOOD -> "US" in countries
                Tag.BOLLYWOOD -> "IN" in countries && "hi" in languages
            }
        }
    }
}
