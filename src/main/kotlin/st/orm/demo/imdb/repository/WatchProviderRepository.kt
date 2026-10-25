package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.WatchProvider
import st.orm.repository.EntityRepository

/**
 * The platforms TMDB names. A lookup stores the platforms it found ([store]),
 * so a renamed one or a new logo is picked up, and a page finds the ones it
 * shows by id.
 */
interface WatchProviderRepository : EntityRepository<WatchProvider, Int> {

    /**
     * Upserts the platforms, in the order of their ids. Lookups run
     * alongside and write overlapping platforms (the big services are on
     * most movies): rows locked in one order make one wait for the other,
     * where two orders could each hold a row the other needs and deadlock.
     */
    fun store(providers: Collection<WatchProvider>) {
        upsert(providers.distinctBy { it.id }.sortedBy { it.id })
    }
}
