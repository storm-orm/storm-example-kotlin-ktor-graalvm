package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.UserGenreSeenCount
import st.orm.template.entityId
import st.orm.template.ref

/**
 * What the visitor has in common with one other person: how many movies
 * both have seen, how many the other has seen in all, and the genres both
 * watch most, as far as the other shares what they watch ([shares]).
 */
data class InCommon(
    val user: User,
    val shares: Boolean,
    val movies: Long,
    val theirs: Long,
    val genres: List<Genre>
) {
    /** The share of what the other watched that the visitor watched too, in whole percent. */
    val percent: Int get() = if (theirs == 0L) 0 else (movies * 100 / theirs).toInt()
}

/**
 * What the visitor has in common with their friends, or with the members
 * of a club: three queries for everyone at once, the counts in common, the
 * counts of all, and the genres per person, and the rest in memory. The
 * people who do not share what they watch are listed without numbers.
 */
class TasteOverlap(
    private val movieSeenRepository: MovieSeenRepository,
    private val movieGenreRepository: MovieGenreRepository
) {
    fun inCommon(user: User, others: List<User>, shares: (User) -> Boolean): List<InCommon> {
        val sharing = others.filter(shares)
        if (sharing.isEmpty()) return others.map { InCommon(it, shares = false, movies = 0, theirs = 0, genres = emptyList()) }
        val inCommon = movieSeenRepository.countSeenInCommon(user, sharing).associate { it.user.entityId() to it.movieCount }
        val seen = movieSeenRepository.countSeen(sharing).associate { it.user.entityId() to it.movieCount }
        val genres = movieGenreRepository.countSeenPerGenre(sharing + user).groupBy { it.user.entityId() }
        val mine = genres[user.id].orEmpty().favourites()
        return others.map { other ->
            if (!shares(other)) InCommon(other, shares = false, movies = 0, theirs = 0, genres = emptyList()) else InCommon(
                user = other,
                shares = true,
                movies = inCommon[other.id] ?: 0,
                theirs = seen[other.id] ?: 0,
                genres = genres[other.id].orEmpty().favourites().filter { genre -> mine.any { it.ref() == genre.ref() } }
            )
        }.sortedWith(compareByDescending<InCommon> { it.movies }.thenBy { it.user.name })
    }

    /** A person's favourite genres: the few they have seen most of. */
    private fun List<UserGenreSeenCount>.favourites(): List<Genre> =
        sortedByDescending { it.movieCount }.take(FAVOURITE_GENRES).map { it.genre }

    private companion object {
        const val FAVOURITE_GENRES = 4
    }
}
