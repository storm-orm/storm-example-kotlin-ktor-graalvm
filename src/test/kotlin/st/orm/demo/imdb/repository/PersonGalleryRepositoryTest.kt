package st.orm.demo.imdb.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.PersonGallery
import st.orm.demo.imdb.model.Photo
import st.orm.demo.imdb.printStatements
import st.orm.repository.repository
import st.orm.template.ORMTemplate
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
        val keanu = personRepository.getById("nm0000206")
        val photos = listOf(
            Photo(url = "https://upload.wikimedia.org/keanu-1.jpg", caption = "Keanu Reeves in 2019"),
            Photo(url = "https://upload.wikimedia.org/keanu-2.jpg")
        )

        capture.run {
            galleryRepository.insert(
                PersonGallery(person = keanu, photos = photos, fetchedAt = Instant.parse("2026-07-03T10:00:00Z"))
            )
            assertEquals(photos, galleryRepository.getById(keanu).photos)
        }
        capture.printStatements("galleryRoundTrip")
        assertEquals(1, capture.count(Operation.INSERT))
    }

    @Test
    fun `a refreshed gallery replaces the stored photos`(orm: ORMTemplate) {
        val personRepository = orm.repository<PersonRepository>()
        val galleryRepository = orm.repository<PersonGalleryRepository>()
        // Morgan Freeman is not touched by other tests in this class — the
        // @StormTest database is shared across the class's test methods.
        val morgan = personRepository.getById("nm0000151")

        // The service refreshes with upsert; on Storm <= 1.11.7 H2 cannot
        // infer the parameter types of the MERGE that upsert generates, so
        // the refresh is exercised as insert + update. Fixed upstream — switch
        // both calls to upsert once the next Storm release is in.
        galleryRepository.insert(
            PersonGallery(morgan, listOf(Photo("https://upload.wikimedia.org/morgan-1.jpg")), Instant.parse("2026-07-03T10:00:00Z"))
        )
        galleryRepository.update(
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
