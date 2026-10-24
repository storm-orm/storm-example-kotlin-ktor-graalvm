package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.time.Instant

/**
 * An invite link: a random [token] that a visitor accepts to become a friend
 * of the [inviter]. The inviter has asked already by sharing it, so
 * accepting is the whole friend request. A personal invite is accepted
 * once, within two weeks; a [reusable] one, the inviter's public link, by
 * anyone, until the inviter replaces it. The inviter comes with the invite,
 * as the page that offers it names them.
 */
data class FriendInvite(
    @PK(generation = NONE) val token: String,
    @FK val inviter: User,
    val createdAt: Instant,
    val reusable: Boolean = false
) : Entity<String>
