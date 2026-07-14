package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.repository.FilmographyEntry
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PersonStatistics
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.template.transaction

/** Everything the person detail page shows. */
data class PersonDetail(
    val person: Person,
    val filmography: List<FilmographyEntry>,
    val statistics: PersonStatistics
)

class PersonService(
    private val personRepository: PersonRepository,
    private val principalRepository: PrincipalRepository
) {

    /** The person page: filmography and statistics in one read-only transaction. */
    suspend fun findPersonDetail(personId: String): PersonDetail? = transaction(readOnly = true) {
        val person = personRepository.findById(personId) ?: return@transaction null
        // A person can hold multiple credits in one movie; show each movie once.
        val filmography = principalRepository.findFilmography(person)
            .distinctBy { it.principal.movie.id }
        PersonDetail(
            person = person,
            filmography = filmography,
            statistics = principalRepository.findStatistics(person)
        )
    }
}
