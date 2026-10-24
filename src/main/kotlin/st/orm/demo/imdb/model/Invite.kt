package st.orm.demo.imdb.model

import st.orm.Discriminator
import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * An invite link: a random [token] that a visitor accepts, to become a
 * friend of the [inviter] ([FriendInvite]) or to join a club
 * ([ClubInvite]). The inviter has asked already by sharing it, so
 * accepting is the whole request. A personal invite is accepted once,
 * within two weeks; a [reusable] one, a public link, by anyone, until it
 * is replaced.
 *
 * Both kinds share one table, told apart by its kind column: Storm's
 * single-table inheritance. A club invite fills the club column, which a
 * friend invite leaves empty, and a read of the invites returns each as
 * its own type.
 */
@Discriminator(column = "kind")
sealed interface Invite : Entity<String> {
    val token: String
    val inviter: User
    val createdAt: Instant
    val reusable: Boolean

    /** Whether the invite can still be accepted: a public one as long as it exists, a personal one for two weeks. */
    fun isOpen(now: Instant): Boolean = reusable || createdAt.isAfter(now.minus(VALIDITY))

    companion object {
        /** How long a personal invite link can be accepted. */
        val VALIDITY: Duration = Duration.ofDays(14)

        private val random = SecureRandom()

        /** A new random token: what the link carries, and the invite's key. */
        fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { random.nextBytes(it) })
    }
}

/** An invite to be friends with the inviter. */
@Discriminator("friend")
data class FriendInvite(
    @PK(generation = NONE) override val token: String,
    @FK override val inviter: User,
    override val createdAt: Instant,
    override val reusable: Boolean = false
) : Invite

/** An invite to join a club, made by one of its admins. */
@Discriminator("club")
data class ClubInvite(
    @PK(generation = NONE) override val token: String,
    @FK override val inviter: User,
    override val createdAt: Instant,
    override val reusable: Boolean = false,
    @FK val club: Club
) : Invite
