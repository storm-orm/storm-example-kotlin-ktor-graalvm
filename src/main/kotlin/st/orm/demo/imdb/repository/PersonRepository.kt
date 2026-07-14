package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Person
import st.orm.repository.EntityRepository

/**
 * Person lookups for the detail page use the inherited CRUD methods
 * (getById, findAllByRef). Search and suggestions run on the PersonSummary
 * projection instead — see PersonSummaryRepository.
 */
interface PersonRepository : EntityRepository<Person, String>
