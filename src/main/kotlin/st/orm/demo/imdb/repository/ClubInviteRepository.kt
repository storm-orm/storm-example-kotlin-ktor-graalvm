package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubInvite
import st.orm.demo.imdb.model.ClubInvite_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.greaterEq
import st.orm.template.less
import st.orm.template.or
import java.time.Instant

/** The invites to clubs: the invite rows of the kind 'club', which only these queries see. */
interface ClubInviteRepository : EntityRepository<ClubInvite, String> {

    /** The club's public invite link, if an admin made one. */
    fun findPublic(club: Club): ClubInvite? =
        find((ClubInvite_.club eq club) and (ClubInvite_.reusable eq true))

    /** Withdraws the club's public invite link, when an admin replaces it. */
    fun removePublic(club: Club) {
        removeAll((ClubInvite_.club eq club) and (ClubInvite_.reusable eq true))
    }

    /**
     * The club's invites still open, by any of its admins: the public one,
     * and the personal ones made since a moment. The public one first, then
     * the newest first.
     */
    fun findOpen(club: Club, personalSince: Instant): List<ClubInvite> =
        select((ClubInvite_.club eq club) and ((ClubInvite_.reusable eq true) or (ClubInvite_.createdAt greaterEq personalSince)))
            .orderByDescending(ClubInvite_.reusable, ClubInvite_.createdAt)
            .resultList

    /** Withdraws one of the club's invites, public or personal: its link stops working. */
    fun removeInvite(club: Club, token: String) {
        removeAll((ClubInvite_.token eq token) and (ClubInvite_.club eq club))
    }

    /** Removes the club's personal invites created before a moment: the ones no longer accepted. */
    fun removeExpired(club: Club, before: Instant) {
        removeAll((ClubInvite_.club eq club) and (ClubInvite_.reusable eq false) and (ClubInvite_.createdAt less before))
    }

    /** Moves the club invites one user made to another, for the sign-in that joins two accounts, so the links keep working. */
    fun moveTo(from: User, into: User) {
        update(findAll(ClubInvite_.inviter eq from).map { it.copy(inviter = into) })
    }
}
