package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.TopTen
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.TopTenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.transaction
import java.time.Instant

/** The visitor's top ten: the movies in their order, and whether there is room for another. */
data class TopTenView(val entries: List<TopTen>) {
    val full: Boolean get() = entries.size >= TopTenService.SIZE
    /** The places still open, to show as empty: 1 to 10 less what is filled. */
    val open: List<Int> get() = (entries.size + 1..TopTenService.SIZE).toList()
}

/**
 * A user's top ten: the ten movies they rate above all, in their order. A
 * movie put in counts as watched, as one cannot rate what one has not
 * seen; the list is dragged into order on its page.
 */
class TopTenService(
    private val topTenRepository: TopTenRepository,
    private val movieRepository: MovieRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val movieSeenService: MovieSeenService,
    private val userRepository: UserRepository
) {
    suspend fun topTenOf(user: User): TopTenView = transaction(readOnly = true) { TopTenView(topTenRepository.findAll(user)) }

    /** The user's top ten as a list of movies, for the pages of others. */
    suspend fun moviesOf(user: User) = transaction(readOnly = true) { topTenRepository.findAll(user).map { it.movie } }

    /** Puts a movie at the end of the top ten, and marks it watched when it was not; nothing when the ten are full or it is in already. */
    suspend fun add(user: User, movieId: String, now: Instant = Instant.now()) {
        val movie = transaction(readOnly = true) { movieRepository.findById(movieId) } ?: return
        val stored = userRepository.touch(user, now)
        val added = transaction {
            val entries = topTenRepository.findAll(stored)
            if (entries.size >= SIZE || entries.any { it.movie.id == movie.id }) return@transaction false
            topTenRepository.insert(TopTen(stored, movie, entries.size + 1, now))
            true
        }
        if (added && !movieSeenRepository.contains(stored, movie)) movieSeenService.toggle(stored, movie.id, historic = true)
    }

    /** Takes a movie out, the ones after it moving up. */
    suspend fun remove(user: User, movieId: String) = transaction {
        val movie = movieRepository.findById(movieId) ?: return@transaction
        topTenRepository.remove(user, movie)
        renumber(topTenRepository.findAll(user))
    }

    /** Puts the top ten in the order given, by movie id; an order that is not the same movies changes nothing. */
    suspend fun reorder(user: User, movieIds: List<String>) = transaction {
        val entries = topTenRepository.findAll(user)
        if (movieIds.toSet() != entries.map { it.movie.id }.toSet() || movieIds.size != entries.size) return@transaction
        val byMovie = entries.associateBy { it.movie.id }
        renumber(movieIds.map { byMovie.getValue(it) })
    }

    /** Writes the positions 1 to n in the order of the list, where they changed. */
    private fun renumber(entries: List<TopTen>) {
        entries.forEachIndexed { i, entry -> if (entry.position != i + 1) topTenRepository.update(entry.copy(position = i + 1)) }
    }

    companion object {
        const val SIZE = 10
    }
}
