package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Invite
import st.orm.repository.EntityRepository
import java.time.Instant

/**
 * The invites of both kinds, as the one invite page reads them: by token,
 * each returned as its own type, [st.orm.demo.imdb.model.FriendInvite] or
 * [st.orm.demo.imdb.model.ClubInvite]. What one kind needs of its own is
 * in [FriendInviteRepository] and [ClubInviteRepository].
 */
interface InviteRepository : EntityRepository<Invite, String> {

    /** The invite of a link, if it can still be accepted. */
    fun findOpen(token: String, now: Instant): Invite? =
        findById(token)?.takeIf { it.isOpen(now) }
}
