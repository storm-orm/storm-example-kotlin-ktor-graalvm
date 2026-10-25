package st.orm.demo.imdb.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieLanguage
import st.orm.demo.imdb.model.MovieTag
import st.orm.demo.imdb.model.Tag
import st.orm.demo.imdb.model.WikidataSource
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
 * each again after [ASK_AGAIN_AFTER], as Wikidata grows.
 */
class WikidataService(
    private val wikidataClient: WikidataClient,
    private val movieRepository: MovieRepository,
    private val wikidataSourceRepository: WikidataSourceRepository,
    private val movieLanguageRepository: MovieLanguageRepository,
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
        movieRepository.findNotAskedOfWikidataSince(now.minus(ASK_AGAIN_AFTER), WINDOW_SIZE).collect { window ->
            val movies = window.content()
            val answers = withContext(Dispatchers.IO) { wikidataClient.findMovies(movies.map { it.id }) }
            transaction { store(movies, answers, now) }
            asked += movies.size
            delay(PAUSE.toMillis())
        }
        if (asked > 0) logger.info("Asked Wikidata about {} movies.", asked)
    }

    /** The answers for a window of movies: the sources, and in place of what they had, the languages and the tags. */
    private fun store(movies: List<Movie>, answers: Map<String, WikidataMovie>, now: Instant) {
        val refs = movies.map { it.ref() }
        wikidataSourceRepository.replace(movies.map { WikidataSource(movie = it.ref(), externalId = answers[it.id]?.itemId, fetchedAt = now) })
        movieLanguageRepository.replace(refs, movies.flatMap { movie ->
            answers[movie.id]?.languages.orEmpty().map { MovieLanguage(movie.ref(), it) }
        })
        movieTagRepository.replace(refs, movies.flatMap { movie ->
            answers[movie.id]?.let(::tagsOf).orEmpty().map { MovieTag(movie.ref(), it) }
        })
    }

    companion object {
        /** The movies one query to Wikidata asks about. */
        private const val WINDOW_SIZE = 200
        private val PAUSE: Duration = Duration.ofSeconds(1)
        private val ASK_AGAIN_AFTER: Duration = Duration.ofDays(180)

        /** The tags of a movie by what Wikidata says of it: each tag has its rule here, which the compiler asks for. */
        fun tagsOf(movie: WikidataMovie): List<Tag> = Tag.entries.filter { tag ->
            when (tag) {
                Tag.HOLLYWOOD -> "US" in movie.countries
                Tag.BOLLYWOOD -> "IN" in movie.countries && "hi" in movie.languages
            }
        }
    }
}
