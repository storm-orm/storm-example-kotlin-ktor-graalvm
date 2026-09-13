package st.orm.demo.imdb.repository

import kotlinx.coroutines.flow.Flow
import st.orm.Ref
import st.orm.Window
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.PersonGallery
import st.orm.demo.imdb.model.PersonGallery_
import st.orm.repository.EntityRepository
import st.orm.template.less
import java.time.Instant

/**
 * The entire persistence layer of the gallery feature: findById, upsert and
 * friends all come with EntityRepository.
 */
interface PersonGalleryRepository : EntityRepository<PersonGallery, Ref<Person>> {

    /**
     * The galleries last fetched before [cutoff], in windows of [windowSize]
     * rows over the key. Each window is one statement that has returned and
     * closed before the loop sees it, so the connection is free while the
     * refresh resolves the persons, calls Wikimedia and writes the window
     * back. A result stream would hold the connection instead and refuse
     * every one of those statements.
     */
    fun findStaleGalleries(cutoff: Instant, windowSize: Int): Flow<Window<PersonGallery>> =
        select()
            .where(PersonGallery_.fetchedAt less cutoff)
            .windows(windowSize)
}
