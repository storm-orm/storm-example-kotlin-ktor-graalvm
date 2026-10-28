package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.ClubMemberPk
import st.orm.demo.imdb.model.ClubMember_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList

interface ClubMemberRepository : EntityRepository<ClubMember, ClubMemberPk> {

    /** A user's membership of a club, by the user's id, or null for one they are not in: a lookup of the pair key. */
    fun findMember(club: Club, userId: String): ClubMember? =
        findById(ClubMemberPk(clubId = club.id, userId = userId))

    /** The user's memberships, by club name; each comes with its club. */
    fun findClubs(user: User): List<ClubMember> =
        select(ClubMember_.user eq user)
            .orderBy(ClubMember_.club.name)
            .resultList

    /** The members of several clubs, admins first, then by name: the club page, and who shares what across the user's clubs. */
    fun findMembers(clubs: List<Club>): List<ClubMember> =
        if (clubs.isEmpty()) emptyList() else select(ClubMember_.club inList clubs)
            .orderByDescending(ClubMember_.admin)
            .orderBy(ClubMember_.user.name)
            .resultList

    /** How many admins a club has: the one that must remain. */
    fun countAdmins(club: Club): Long =
        count((ClubMember_.club eq club) and (ClubMember_.admin eq true))

    /** How many members a club has. */
    fun countMembers(club: Club): Long =
        count(ClubMember_.club eq club)

    /**
     * Moves one user's memberships to another, for the sign-in that joins
     * two accounts. A club both accounts are in keeps the one that stays, an
     * admin if either was; the user is part of the key, so the others are
     * inserted for the account that stays and removed for the one that goes.
     */
    fun moveTo(from: User, into: User) {
        val kept = findClubs(into).associateBy { it.club.id }
        val moving = findClubs(from)
        update(moving.mapNotNull { member -> kept[member.club.id]?.takeIf { member.admin && !it.admin }?.copy(admin = true) })
        insert(moving.filter { it.club.id !in kept }.map { it.copy(id = ClubMemberPk(it.club.id, into.id), user = into) })
        removeAll(ClubMember_.user eq from)
    }
}
