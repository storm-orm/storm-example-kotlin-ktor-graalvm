package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.FriendRequest
import st.orm.demo.imdb.model.FriendRequestPk
import st.orm.demo.imdb.model.FriendRequest_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.or

interface FriendRequestRepository : EntityRepository<FriendRequest, FriendRequestPk> {

    /** The request from one user to another, if there is one: a lookup of the pair key. */
    fun findRequest(requester: User, recipient: User): FriendRequest? =
        findById(FriendRequestPk(requesterId = requester.id, recipientId = recipient.id))

    /** The requests the user has to answer, the latest first: those they did not put off with "not now". */
    fun findOpenFor(recipient: User): List<FriendRequest> =
        select((FriendRequest_.recipient eq recipient) and (FriendRequest_.dismissed eq false))
            .orderByDescending(FriendRequest_.createdAt)
            .resultList

    /** How many requests the user has to answer, for the badge on the Community tab. */
    fun countOpenFor(recipient: User): Long =
        count((FriendRequest_.recipient eq recipient) and (FriendRequest_.dismissed eq false))

    /** The requests the user sent and the others have not accepted, "not now" included: the requester is never told. */
    fun findSentBy(requester: User): List<FriendRequest> =
        select(FriendRequest_.requester eq requester)
            .orderByDescending(FriendRequest_.createdAt)
            .resultList

    /** The requests to and from the user, either way, for their statuses on a club's page. */
    fun findInvolving(user: User): List<FriendRequest> =
        findAll((FriendRequest_.requester eq user) or (FriendRequest_.recipient eq user))

    /** Removes the requests between two users, both ways: they became friends. */
    fun removeBetween(user: User, other: User) {
        removeAll(((FriendRequest_.requester eq user) and (FriendRequest_.recipient eq other)) or ((FriendRequest_.requester eq other) and (FriendRequest_.recipient eq user)))
    }

    /** Removes the requests sent from a club to or by a member who leaves it. */
    fun removeFrom(club: Club, member: User) {
        removeAll((FriendRequest_.club eq club) and ((FriendRequest_.requester eq member) or (FriendRequest_.recipient eq member)))
    }

    /**
     * Moves one user's requests to another, for the sign-in that joins two
     * accounts. The users are part of the key, so the requests are removed
     * and inserted again for the account that stays, except one between the
     * two accounts and one the account that stays has already.
     */
    fun moveTo(from: User, into: User) {
        val moving = findInvolving(from)
        removeAll((FriendRequest_.requester eq from) or (FriendRequest_.recipient eq from))
        insert(moving
            .map {
                if (it.requester.id == from.id) it.copy(id = FriendRequestPk(into.id, it.recipient.id), requester = into)
                else it.copy(id = FriendRequestPk(it.requester.id, into.id), recipient = into)
            }
            .filter { it.requester.id != it.recipient.id }
            .distinctBy { it.id }
            .filterNot { existsById(it.id) })
    }
}
