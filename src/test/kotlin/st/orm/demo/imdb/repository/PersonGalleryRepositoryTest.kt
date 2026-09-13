package st.orm.demo.imdb.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.PersonGallery
import st.orm.demo.imdb.model.Photo
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.CapturedSql.Operation
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class PersonGalleryRepositoryTest {

    @Test
    fun `a gallery round-trips its photos through the json column`(orm: ORMTemplate, capture: SqlCapture) {
        val personRepository = orm.repository<PersonRepository>()
        val galleryRepository = orm.repository<PersonGalleryRepository>()
        val keanu = personRepository.getById("nm0000206").ref()
        val photos = listOf(
            Photo(url = "https://upload.wikimedia.org/keanu-1.jpg", caption = "Keanu Reeves in 2019"),
            Photo(url = "https://upload.wikimedia.org/keanu-2.jpg")
        )

        capture.record {
            galleryRepository.insert(
                PersonGallery(person = keanu, photos = photos, fetchedAt = Instant.parse("2026-07-03T10:00:00Z"))
            )
            assertEquals(photos, galleryRepository.getById(keanu).photos)
        }
        capture.printStatements("galleryRoundTrip")
        assertEquals(1, capture.count(Operation.INSERT))
    }

    @Test
    fun `stale galleries are read in windows of their own statement`(orm: ORMTemplate, capture: SqlCapture) {
        val personRepository = orm.repository<PersonRepository>()
        val galleryRepository = orm.repository<PersonGalleryRepository>()
        val cutoff = Instant.parse("2026-08-01T00:00:00Z")
        val stalePersonIds = listOf("nm0000206", "nm0000401", "nm0000151", "nm0000209", "nm0000237")
        galleryRepository.insert(stalePersonIds.map { personId ->
            PersonGallery(
                person = personRepository.getById(personId).ref(),
                photos = listOf(Photo("https://upload.wikimedia.org/$personId.jpg")),
                fetchedAt = Instant.parse("2026-07-03T10:00:00Z")
            )
        })
        galleryRepository.insert(
            PersonGallery(
                person = personRepository.getById("nm0000199").ref(),
                photos = listOf(Photo("https://upload.wikimedia.org/nm0000199.jpg")),
                fetchedAt = Instant.parse("2026-09-01T10:00:00Z")
            )
        )

        val windows = capture.execute { runBlocking { galleryRepository.findStaleGalleries(cutoff, 2).toList() } }
        capture.printStatements("findStaleGalleries")

        // Three windows over the five stale galleries, each read by one statement
        // of its own; the fresh gallery is not in any of them.
        assertEquals(3, windows.size)
        assertEquals(3, capture.count(Operation.SELECT))
        assertEquals(listOf(2, 2, 1), windows.map { it.content().size })
        assertEquals(stalePersonIds.size, windows.sumOf { it.content().size })
    }

    @Test
    fun `a refreshed gallery replaces the stored photos`(orm: ORMTemplate) {
        val personRepository = orm.repository<PersonRepository>()
        val galleryRepository = orm.repository<PersonGalleryRepository>()
        // @StormTest rolls each test back, so this person has no gallery yet
        // however the class orders its methods.
        val morgan = personRepository.getById("nm0000151").ref()

        // The refresh runs the way the service does it: upsert writes the
        // first gallery and replaces it on the second call.
        galleryRepository.upsert(
            PersonGallery(morgan, listOf(Photo("https://upload.wikimedia.org/morgan-1.jpg")), Instant.parse("2026-07-03T10:00:00Z"))
        )
        galleryRepository.upsert(
            PersonGallery(
                morgan,
                listOf(
                    Photo("https://upload.wikimedia.org/morgan-2.jpg", caption = "Morgan Freeman in 2018"),
                    Photo("https://upload.wikimedia.org/morgan-3.jpg")
                ),
                Instant.parse("2026-07-03T11:00:00Z")
            )
        )

        val stored = galleryRepository.getById(morgan)
        assertEquals(2, stored.photos.size)
        assertEquals("https://upload.wikimedia.org/morgan-2.jpg", stored.photos.first().url)
        assertEquals(Instant.parse("2026-07-03T11:00:00Z"), stored.fetchedAt)
    }
}
