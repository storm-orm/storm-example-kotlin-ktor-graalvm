package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class FriendshipPk(
    val userId: String,
    val friendId: String
)

/**
 * One direction of a friendship: [friend] is a friend of [user]. A friendship
 * is two rows, one each way, so a user's friends are a lookup of their own
 * rows, and either side ends it by removing both. The user is a Ref, as the
 * rows are always read for one user; the friend comes with the row, since
 * what to show of a friend is read from them.
 */
data class Friendship(
    @PK(generation = NONE) val id: FriendshipPk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val friend: User,
    val since: Instant
) : Entity<FriendshipPk> {
    constructor(user: User, friend: User, since: Instant) : this(
        id = FriendshipPk(userId = user.id, friendId = friend.id),
        user = user.ref(),
        friend = friend,
        since = since
    )
}
