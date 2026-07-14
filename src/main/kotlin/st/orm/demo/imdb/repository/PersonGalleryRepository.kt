package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.PersonGallery
import st.orm.repository.EntityRepository

/**
 * The entire persistence layer of the gallery feature: findById, upsert and
 * friends all come with EntityRepository.
 */
interface PersonGalleryRepository : EntityRepository<PersonGallery, Person>
