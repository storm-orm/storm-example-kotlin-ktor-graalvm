package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.TopTen
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.TopTenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.transaction
import java.time.Instant

/** The visitor's top ten in its order, their honourable mentions in none, and the room left in each. */
data class TopTenView(val entries: List<TopTen>, val mentions: List<TopTen>) {
    val full: Boolean get() = entries.size >= TopTenService.SIZE
    val mentionsFull: Boolean get() = mentions.size >= TopTenService.MENTIONS
    /** The places still open, to show as empty: 1 to 10 less what is filled. */
    val open: List<Int> get() = (entries.size + 1..TopTenService.SIZE).toList()
}

/** Someone's top ten and honourable mentions, as movies, for the pages of others. */
data class TopTenMovies(val topTen: List<Movie>, val mentions: List<Movie>)

/**
 * A user's top ten: the ten movies they rate above all, in their order,
 * and under them their honourable mentions, fifteen at most, in no order.
 * A movie put in counts as watched, as one cannot rate what one has not
 * seen; the ten are dragged into order on their page, and a movie taken
 * out of a full ten to make room goes down to the mentions.
 */
class TopTenService(
    private val topTenRepository: TopTenRepository,
    private val movieRepository: MovieRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val movieSeenService: MovieSeenService,
    private val userRepository: UserRepository
) {
    suspend fun topTenOf(user: User): TopTenView = transaction(readOnly = true) { viewOf(user) }

    private fun viewOf(user: User): TopTenView = topTenRepository.findAll(user).partition { !it.mention }.let { (ten, mentions) -> TopTenView(ten, mentions) }

    /** The user's top ten and mentions as movies, for the pages of others. */
    suspend fun moviesOf(user: User): TopTenMovies = transaction(readOnly = true) {
        viewOf(user).let { TopTenMovies(it.entries.map { e -> e.movie }, it.mentions.map { e -> e.movie }) }
    }

    /**
     * Puts a movie in: at the end of the top ten while there is room, else
     * among the mentions ([asMention] puts it there to begin with), and
     * marks it watched when it was not. Nothing when it is in already, or
     * where it would go is full.
     */
    suspend fun add(user: User, movieId: String, asMention: Boolean = false, now: Instant = Instant.now()) {
        val movie = transaction(readOnly = true) { movieRepository.findById(movieId) } ?: return
        val stored = userRepository.touch(user, now)
        val added = transaction {
            val view = viewOf(stored)
            if ((view.entries + view.mentions).any { it.movie.id == movie.id }) return@transaction false
            when {
                !asMention && !view.full -> topTenRepository.insert(TopTen(stored, movie, view.entries.size + 1, now))
                !view.mentionsFull -> topTenRepository.insert(TopTen(stored, movie, 0, now, mention = true))
                else -> return@transaction false
            }
            true
        }
        if (added && !movieSeenRepository.contains(stored, movie)) movieSeenService.toggle(stored, movie.id, historic = true)
    }

    /** Takes a movie out: from a full ten it goes down to the mentions while there is room, from the mentions it goes; the ten close up. */
    suspend fun remove(user: User, movieId: String, now: Instant = Instant.now()) = transaction {
        val view = viewOf(user)
        val entry = (view.entries + view.mentions).firstOrNull { it.movie.id == movieId } ?: return@transaction
        if (!entry.mention && view.full && !view.mentionsFull) topTenRepository.update(entry.copy(position = 0, addedAt = now, mention = true))
        else topTenRepository.remove(user, entry.movie)
        renumber(viewOf(user).entries)
    }

    /** Lifts a mention into the top ten, at the end, while there is room. */
    suspend fun promote(user: User, movieId: String) = transaction {
        val view = viewOf(user)
        val entry = view.mentions.firstOrNull { it.movie.id == movieId } ?: return@transaction
        if (!view.full) topTenRepository.update(entry.copy(position = view.entries.size + 1, mention = false))
    }

    /** Puts the top ten in the order given, by movie id; an order that is not the same movies changes nothing. */
    suspend fun reorder(user: User, movieIds: List<String>) = transaction {
        val entries = viewOf(user).entries
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
        const val MENTIONS = 15
    }
}
