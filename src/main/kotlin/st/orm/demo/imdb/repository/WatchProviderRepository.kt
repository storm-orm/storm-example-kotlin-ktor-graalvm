package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.WatchProvider
import st.orm.repository.EntityRepository

/**
 * The platforms TMDB names. The built-in methods are all it takes: a lookup
 * upserts the platforms it found, so a renamed one or a new logo is picked
 * up, and a page finds the ones it shows by id.
 */
interface WatchProviderRepository : EntityRepository<WatchProvider, Int>
