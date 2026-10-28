package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import java.time.Instant

data class ClubMemberPk(
    val clubId: Long,
    val userId: String
)

/**
 * A user's membership of a club, keyed by the pair: whether they are one of
 * its admins, and what they share with its members. Each of the three is
 * the member's own choice for this club, or null to follow their club
 * defaults on [User]; the effective values are [sharesSeen], [sharesName]
 * and [sharesDates]. The club and the user come with the row, as the pages
 * show both.
 */
data class ClubMember(
    @PK(generation = NONE) val id: ClubMemberPk,
    @FK @Persist(insertable = false, updatable = false) val club: Club,
    @FK @Persist(insertable = false, updatable = false) val user: User,
    val admin: Boolean,
    val joinedAt: Instant,
    val shareSeen: Boolean? = null,
    val shareName: Boolean? = null,
    val shareDates: Boolean? = null
) : Entity<ClubMemberPk> {
    constructor(club: Club, user: User, admin: Boolean, joinedAt: Instant) : this(
        id = ClubMemberPk(clubId = club.id, userId = user.id),
        club = club,
        user = user,
        admin = admin,
        joinedAt = joinedAt
    )

    val sharesSeen: Boolean get() = shareSeen ?: user.clubShareSeen
    val sharesName: Boolean get() = shareName ?: user.clubShareName
    val sharesDates: Boolean get() = shareDates ?: user.clubShareDates

    /** Whether this club follows the member's club defaults rather than settings of its own. */
    val followsDefaults: Boolean get() = shareSeen == null && shareName == null && shareDates == null
}
