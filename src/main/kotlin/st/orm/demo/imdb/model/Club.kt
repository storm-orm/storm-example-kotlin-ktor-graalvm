package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.PK
import java.time.Instant

/**
 * A film club: coworkers or a group of friends who see what the others
 * watched, as far as each shares it. Its members, and who of them are its
 * admins, are its [ClubMember]s; there is always at least one admin.
 */
data class Club(
    @PK val id: Long = 0,
    val name: String,
    val createdAt: Instant
) : Entity<Long>
