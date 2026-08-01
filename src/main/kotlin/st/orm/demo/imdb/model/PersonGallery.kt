package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.Json
import st.orm.PK
import st.orm.Ref
import java.time.Instant

/** A single photo in a person's gallery. */
@Serializable
data class Photo(
    val url: String,
    val caption: String? = null
)

/**
 * The photo gallery of a person, fetched from Wikimedia on first view and
 * stored for every request after that. A dependent one-to-one like Rating:
 * the primary key is the foreign key to the person. The photos live in a
 * single JSON column — a gallery is opaque, always read whole and never
 * filtered by element, so a separate photo table would buy nothing.
 *
 * The key is a Ref: whoever asks for a gallery already holds the person, and
 * nothing reads the person back off it, so reading a gallery touches the
 * gallery table alone. Queries can still reach the person through the key.
 */
data class PersonGallery(
    @PK(generation = NONE) @FK val person: Ref<Person>,
    @Json val photos: List<Photo>,
    val fetchedAt: Instant
) : Entity<Ref<Person>>
