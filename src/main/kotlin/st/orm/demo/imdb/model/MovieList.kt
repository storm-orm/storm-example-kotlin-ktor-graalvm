package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.PK
import java.time.Instant

/**
 * A list of movies someone curated: "Best heist films". A user's own list
 * is public, for anyone with its link, and the lists page shows it to
 * others; a club's list, with its [club], is its members', and every
 * member adds to it. The [owner] made it, and renames or removes it, as
 * the club's admins do a club's list. Its movies are its [MovieListItem]s.
 */
data class MovieList(
    @PK val id: Long = 0,
    val name: String,
    val description: String?,
    @FK val owner: User,
    @FK val club: Club?,
    val createdAt: Instant,
    /** When a movie was last added or taken off, or the list renamed: the lists page shows the busiest first. */
    val updatedAt: Instant
) : Entity<Long>
