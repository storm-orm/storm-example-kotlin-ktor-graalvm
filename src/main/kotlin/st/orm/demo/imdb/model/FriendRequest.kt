package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import java.time.Instant

data class FriendRequestPk(
    val requesterId: String,
    val recipientId: String
)

/**
 * A request from one club member to another to be friends, keyed by the
 * pair: one request each way at most. It names the [club] it was sent from,
 * which both were in. [dismissed] is the recipient's "not now": the request
 * leaves their list, and the requester still sees it as sent.
 */
data class FriendRequest(
    @PK(generation = NONE) val id: FriendRequestPk,
    @FK @Persist(insertable = false, updatable = false) val requester: User,
    @FK @Persist(insertable = false, updatable = false) val recipient: User,
    @FK val club: Club,
    val createdAt: Instant,
    val dismissed: Boolean = false
) : Entity<FriendRequestPk> {
    constructor(requester: User, recipient: User, club: Club, createdAt: Instant) : this(
        id = FriendRequestPk(requesterId = requester.id, recipientId = recipient.id),
        requester = requester,
        recipient = recipient,
        club = club,
        createdAt = createdAt
    )
}
