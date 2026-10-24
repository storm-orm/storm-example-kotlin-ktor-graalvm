package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.Friendship
import st.orm.demo.imdb.model.FriendshipPk
import st.orm.demo.imdb.model.Friendship_
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.or
import java.time.Instant

interface FriendshipRepository : EntityRepository<Friendship, FriendshipPk> {

    /** The user's friends, by name; each friend comes with the row. */
    fun findFriends(user: User): List<Friendship> =
        select(Friendship_.user eq user)
            .orderBy(Friendship_.friend.name)
            .resultList

    /** Whether two users are friends. */
    fun areFriends(user: User, other: User): Boolean =
        existsById(FriendshipPk(userId = user.id, friendId = other.id))

    /** Makes two users friends, both ways; a direction that exists already stays as it is. */
    fun befriend(user: User, friend: User, since: Instant) {
        insert(listOf(Friendship(user, friend, since), Friendship(friend, user, since)).filterNot { existsById(it.id) })
    }

    /** Ends a friendship, both ways. */
    fun unfriend(user: User, friend: User) {
        removeAll(((Friendship_.user eq user) and (Friendship_.friend eq friend)) or ((Friendship_.user eq friend) and (Friendship_.friend eq user)))
    }

    /**
     * Moves one user's friends to another, for the sign-in that joins two
     * accounts: every friend of the account that goes becomes a friend of
     * the one that stays, from the day they first were.
     */
    fun moveTo(from: User, into: User) {
        findFriends(from).filter { it.friend.id != into.id }.forEach { befriend(into, it.friend, it.since) }
        removeAll((Friendship_.user eq from) or (Friendship_.friend eq from))
    }
}
