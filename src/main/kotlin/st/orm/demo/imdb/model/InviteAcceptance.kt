package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

data class InviteAcceptancePk(
    val inviterId: String,
    val inviteeId: String
)

/**
 * Someone who came through another's invite, friend or club: kept for good,
 * whatever becomes of the invite or the friendship, since the levels count
 * the people one brought in. One row per pair: a second invite accepted
 * later changes nothing.
 */
data class InviteAcceptance(
    @PK(generation = NONE) val id: InviteAcceptancePk,
    @FK @Persist(insertable = false, updatable = false) val inviter: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val invitee: Ref<User>,
    val acceptedAt: Instant
) : Entity<InviteAcceptancePk> {
    constructor(inviter: User, invitee: User, acceptedAt: Instant) : this(
        id = InviteAcceptancePk(inviterId = inviter.id, inviteeId = invitee.id),
        inviter = inviter.ref(),
        invitee = invitee.ref(),
        acceptedAt = acceptedAt
    )
}
