package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.FriendInvite
import st.orm.demo.imdb.model.FriendInvite_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.less
import java.time.Instant

/** The invites to be friends: the invite rows of the kind 'friend', which only these queries see. */
interface FriendInviteRepository : EntityRepository<FriendInvite, String> {

    /** The user's public invite link, if they made one. */
    fun findPublic(user: User): FriendInvite? =
        find((FriendInvite_.inviter eq user) and (FriendInvite_.reusable eq true))

    /** Withdraws the user's public invite link, when they replace it. */
    fun removePublic(user: User) {
        removeAll((FriendInvite_.inviter eq user) and (FriendInvite_.reusable eq true))
    }

    /** Removes the user's personal invites created before a moment: the ones no longer accepted. */
    fun removeExpired(user: User, before: Instant) {
        removeAll((FriendInvite_.inviter eq user) and (FriendInvite_.reusable eq false) and (FriendInvite_.createdAt less before))
    }
}
