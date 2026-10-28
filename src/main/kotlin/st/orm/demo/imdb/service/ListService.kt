package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.MovieList
import st.orm.demo.imdb.model.MovieListItem
import st.orm.demo.imdb.model.MovieListItemPk
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.MovieListItemRepository
import st.orm.demo.imdb.repository.MovieListRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.template.entityId
import st.orm.template.transaction
import java.time.Instant

/** A list as a card on the lists page: how many movies it has, and the posters of the latest added. */
data class ListSummary(val list: MovieList, val movieCount: Long, val posters: List<Movie>)

/**
 * The lists page: the visitor's own lists, their clubs' lists, and the
 * public lists of others, with the clubs they can start a list for.
 */
data class ListsView(
    val own: List<ListSummary>,
    val clubLists: List<ListSummary>,
    val others: List<ListSummary>,
    val clubs: List<Club>
)

/**
 * A list's page: its movies, the first added first, and what the visitor
 * may do with it: add and take off movies ([canEdit]), and rename or
 * remove it ([canManage]).
 */
data class ListView(
    val list: MovieList,
    val items: List<MovieListItem>,
    val canEdit: Boolean,
    val canManage: Boolean
)

/** A list the visitor can add a movie to, for the movie page's menu: whether the movie is on it. */
@Serializable
data class ListChoice(val id: Long, val name: String, val club: String?, val contains: Boolean)

/**
 * Curated lists: a signed-in visitor makes lists of their own, public to
 * anyone with the link, and lists for their clubs, which the members see
 * and add to. The owner renames and removes a list, and so do a club's
 * admins its lists. A list that changes moves up the lists page.
 */
class ListService(
    private val movieListRepository: MovieListRepository,
    private val movieListItemRepository: MovieListItemRepository,
    private val clubRepository: ClubRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val movieRepository: MovieRepository
) {

    /** The lists page for a signed-in visitor. */
    suspend fun listsOf(user: User): ListsView = transaction(readOnly = true) {
        val clubs = clubMemberRepository.findClubs(user).map { it.club }
        val own = movieListRepository.findOwn(user)
        val clubLists = movieListRepository.findOfClubs(clubs)
        val others = movieListRepository.findOthers(user, OTHERS_LIMIT)
        val summaries = summaries(own + clubLists + others)
        ListsView(
            own = own.map(summaries::getValue),
            clubLists = clubLists.map(summaries::getValue),
            // The others' lists worth a look: a few movies at least.
            others = others.map(summaries::getValue).filter { it.movieCount >= OTHERS_MINIMUM },
            clubs = clubs.sortedBy { it.name }
        )
    }

    /** A club's lists, for its page. */
    suspend fun listsOf(club: Club): List<ListSummary> = transaction(readOnly = true) {
        val lists = movieListRepository.findOfClubs(listOf(club))
        val summaries = summaries(lists)
        lists.map(summaries::getValue)
    }

    /** Each list's count and its latest posters, from two queries for all of them. */
    private fun summaries(lists: List<MovieList>): Map<MovieList, ListSummary> {
        val counts = movieListItemRepository.countMovies(lists).associate { it.list.entityId() to it.movies }
        val posters = movieListItemRepository.findItems(lists).groupBy({ it.list.entityId() }, { it.movie })
        return lists.associateWith { ListSummary(it, counts[it.id] ?: 0, posters[it.id].orEmpty().take(POSTERS)) }
    }

    /** A list's page, or null for a list that is gone, or a club's list for someone not in the club. */
    suspend fun listOf(user: User, listId: Long): ListView? = transaction(readOnly = true) {
        val list = movieListRepository.findById(listId) ?: return@transaction null
        val member = list.club?.let { clubMemberRepository.findMember(it, user.id) ?: return@transaction null }
        ListView(
            list = list,
            items = movieListItemRepository.findItems(list),
            canEdit = canEdit(user, list, member),
            canManage = canManage(user, list, member)
        )
    }

    /** Who adds to a list and takes off it: the owner of a list of their own, every member of a club's. */
    private fun canEdit(user: User, list: MovieList, member: ClubMember?): Boolean =
        if (list.club == null) list.owner.id == user.id else member != null

    /** Who renames and removes a list: its owner, and a club's admins. */
    private fun canManage(user: User, list: MovieList, member: ClubMember?): Boolean =
        list.owner.id == user.id || member?.admin == true

    /** The visitor's membership of a list's club, or null for a list of their own or a club they are not in. */
    private fun membership(user: User, list: MovieList): ClubMember? =
        list.club?.let { clubMemberRepository.findMember(it, user.id) }

    /**
     * Makes a list, of the visitor's own or, with a [clubId] of a club they
     * are in, the club's, with a [movieId] on it already where it was made
     * from a movie's page. Null for a club the visitor is not in.
     */
    suspend fun create(user: User, name: String, description: String?, clubId: Long?, movieId: String?, now: Instant = Instant.now()): MovieList? = transaction {
        val club = clubId?.let { id -> clubRepository.findById(id)?.takeIf { clubMemberRepository.findMember(it, user.id) != null } ?: return@transaction null }
        val list = movieListRepository.insertAndFetch(MovieList(
            name = name.trim().take(NAME_LENGTH),
            description = description?.trim()?.take(DESCRIPTION_LENGTH)?.ifEmpty { null },
            owner = user,
            club = club,
            createdAt = now,
            updatedAt = now
        ))
        movieId?.let(movieRepository::findById)?.let { movieListItemRepository.insert(MovieListItem(list, it, user, now)) }
        list
    }

    /** Renames a list and sets what it is about; for whoever manages it. */
    suspend fun update(user: User, listId: Long, name: String, description: String?, now: Instant = Instant.now()) = transaction {
        val list = movieListRepository.findById(listId) ?: return@transaction
        if (!canManage(user, list, membership(user, list))) return@transaction
        movieListRepository.update(list.copy(
            name = name.trim().take(NAME_LENGTH),
            description = description?.trim()?.take(DESCRIPTION_LENGTH)?.ifEmpty { null },
            updatedAt = now
        ))
    }

    /** Removes a list, its movies with it; for whoever manages it. Returns whether it was removed. */
    suspend fun remove(user: User, listId: Long): Boolean = transaction {
        val list = movieListRepository.findById(listId) ?: return@transaction false
        if (!canManage(user, list, membership(user, list))) return@transaction false
        movieListRepository.remove(list)
        true
    }

    /**
     * Puts a movie on a list, or takes it off when it is on it; for whoever
     * adds to the list. Returns whether the movie is on the list now, or
     * null when the visitor may not change it or either is unknown.
     */
    suspend fun toggle(user: User, listId: Long, movieId: String, now: Instant = Instant.now()): Boolean? = transaction {
        val list = movieListRepository.findById(listId) ?: return@transaction null
        if (!canEdit(user, list, membership(user, list))) return@transaction null
        val movie = movieRepository.findById(movieId) ?: return@transaction null
        val existing = movieListItemRepository.findById(MovieListItemPk(listId = list.id, movieId = movie.id))
        if (existing != null) movieListItemRepository.remove(existing) else movieListItemRepository.insert(MovieListItem(list, movie, user, now))
        movieListRepository.update(list.copy(updatedAt = now))
        existing == null
    }

    /** The movie a list is started from, on a movie's page, for the form to name it. */
    suspend fun movie(movieId: String): Movie? = transaction(readOnly = true) { movieRepository.findById(movieId) }

    /** The lists the visitor adds to, their own and their clubs', each saying whether the movie is on it. */
    suspend fun choicesFor(user: User, movieId: String): List<ListChoice> = transaction(readOnly = true) {
        val movie = movieRepository.findById(movieId) ?: return@transaction emptyList()
        val lists = movieListRepository.findOwn(user) + movieListRepository.findOfClubs(clubMemberRepository.findClubs(user).map { it.club })
        val with = movieListItemRepository.findListsWith(lists, movie).map { it.list.entityId() }.toSet()
        lists.map { ListChoice(it.id, it.name, it.club?.name, it.id in with) }
    }

    companion object {
        const val NAME_LENGTH = 80
        const val DESCRIPTION_LENGTH = 400
        private const val POSTERS = 4
        private const val OTHERS_LIMIT = 48
        private const val OTHERS_MINIMUM = 3
    }
}
