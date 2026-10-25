package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Source
import st.orm.demo.imdb.repository.ImdbSourceRepository
import st.orm.template.entityId
import st.orm.template.transaction

/**
 * Finds movies by the ids their sources give them, for imports that name
 * movies so: each source's ids are read from its own rows of the sources
 * table, and TMDB's not stored yet are asked of TMDB ([TmdbService]). A new
 * source is one more branch here, which the compiler asks for.
 */
class MovieSourceService(
    private val imdbSourceRepository: ImdbSourceRepository,
    private val tmdbService: TmdbService
) {
    /**
     * The movies with these ids at a source, by id; an id no movie is known
     * by is left out. TMDB is asked only when [askTmdb] says the import may.
     */
    suspend fun movieIds(source: Source, externalIds: Set<String>, askTmdb: Boolean): Map<String, String> = when (source) {
        Source.IMDB -> transaction(readOnly = true) {
            imdbSourceRepository.findByExternalIds(externalIds).associate { it.externalId to it.movie.entityId() }
        }
        Source.TMDB -> tmdbService.movieIds(externalIds, askTmdb)
    }
}
