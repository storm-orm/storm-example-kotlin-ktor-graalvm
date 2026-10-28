package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.InviteAcceptance
import st.orm.demo.imdb.model.InviteAcceptancePk
import st.orm.demo.imdb.model.InviteAcceptance_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.eq
import java.time.Instant

interface InviteAcceptanceRepository : EntityRepository<InviteAcceptance, InviteAcceptancePk> {

    /** Records that the invitee came through the inviter, once: a second invite accepted later changes nothing. */
    fun record(inviter: User, invitee: User, now: Instant) {
        val acceptance = InviteAcceptance(inviter, invitee, now)
        if (!existsById(acceptance.id)) insert(acceptance)
    }

    /** How many people came through the user's invites. */
    fun countFor(inviter: User): Long = count(InviteAcceptance_.inviter eq inviter)
}
