package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.DIRECTOR
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.template.entityId
import st.orm.template.ref
import kotlin.random.Random

/**
 * Something movies have in common: the key that tells it apart ("dir:nm0634240"),
 * how the reveal says it ("Directed by Christopher Nolan"), and how hard it is to
 * spot: 1 for a director, 2 for an actor.
 */
@Serializable
data class Trait(val key: String, val label: String, val level: Int)

/** An Odd One Out puzzle: four movies, the one that does not belong, and what the other three share. */
@Serializable
data class OddPuzzle(val movies: List<String>, val odd: String, val trait: Trait)

/** A group of a Movie Connections grid: its four movies and what they share. */
@Serializable
data class ConnectionGroup(val movies: List<String>, val trait: Trait)

/**
 * Makes the daily puzzles out of who made the catalogue's movies: the
 * director and the top-billed actors. Every puzzle has one answer only: an
 * Odd One Out's four movies have no other three in common, and a Connections
 * grid's groups share no one with one another's movies. A puzzle never holds
 * two movies whose titles share a word, such as a movie and its sequel, which
 * would give the answer away.
 */
class PuzzleFactory(private val principalRepository: PrincipalRepository) {

    /** The directors and the top-billed actors of each of the movies, by movie id, read in one query. */
    fun traitsOf(movies: List<Movie>): Map<String, Set<Trait>> {
        val traits = movies.associate { it.id to mutableSetOf<Trait>() }
        principalRepository.findTasteCredits(movies.map { it.ref() }, TOP_BILLED).forEach { credit ->
            val person = credit.person
            val trait = when (credit.category) {
                DIRECTOR -> Trait("dir:${person.id}", "Directed by ${person.primaryName}", 1)
                in ACTING_CATEGORIES -> Trait("act:${person.id}", "With ${person.primaryName}", 2)
                else -> null
            }
            trait?.let { traits[credit.movie.entityId()]?.add(it) }
        }
        return traits
    }

    /**
     * A day's Odd One Out: [count] puzzles, no movie in two of them. Each is
     * three movies that share a director or an actor and one that does not,
     * which no other three of the four share anyone with.
     */
    fun oddOneOut(movies: List<Movie>, traits: Map<String, Set<Trait>>, random: Random, count: Int): List<OddPuzzle> {
        val names = namesOf(movies)
        val used = mutableSetOf<String>()
        return (0 until count).mapNotNull {
            oddPuzzle(movies.filter { it.id !in used }, traits, names, random)?.also { used += it.movies }
        }
    }

    private fun oddPuzzle(movies: List<Movie>, traits: Map<String, Set<Trait>>, names: Map<String, Set<String>>, random: Random): OddPuzzle? {
        val byTrait = byTrait(movies, traits, minimum = 3)
        for (trait in byTrait.keys.shuffled(random)) {
            val three = distinctNames(byTrait.getValue(trait).shuffled(random), names, taken = emptyList()).take(3)
            if (three.size < 3) continue
            val others = distinctNames(movies.map { it.id }.filter { trait !in traits.getValue(it) }.shuffled(random), names, three).take(ODD_TRIES)
            for (odd in others) {
                val four = three + odd
                // One answer only: no one but this trait's person is shared by three of the four.
                val shared = four.flatMap { traits.getValue(it) }.groupingBy { it }.eachCount().filterValues { it >= 3 }.keys
                if (shared == setOf(trait)) return OddPuzzle(four.shuffled(random), odd, trait)
            }
        }
        return null
    }

    /**
     * A day's Movie Connections: four groups of four, each a director or an
     * actor, the directors first, every group's person in its own four of the
     * sixteen alone, so the grid has one way to solve it. Null when none was found.
     */
    fun connections(movies: List<Movie>, traits: Map<String, Set<Trait>>, random: Random): List<ConnectionGroup>? {
        val names = namesOf(movies)
        val byTrait = byTrait(movies, traits, minimum = 4)
        repeat(GRID_TRIES) {
            val chosen = byTrait.keys.shuffled(random).take(4)
            if (chosen.size < 4) return null
            val taken = mutableListOf<String>()
            val groups = chosen.map { trait ->
                val candidates = byTrait.getValue(trait).filter { chosen.none { other -> other != trait && other in traits.getValue(it) } }
                val four = distinctNames(candidates.shuffled(random), names, taken).take(4)
                taken += four
                ConnectionGroup(four, trait)
            }
            if (groups.any { it.movies.size < 4 }) return@repeat
            // One way to solve it: of the sixteen, only a group's own four have its person.
            val sixteen = groups.flatMap { it.movies }
            if (groups.all { group -> sixteen.count { group.trait in traits.getValue(it) } == 4 }) return groups.sortedBy { it.trait.level }
        }
        return null
    }

    /** The traits had by at least [minimum] of the movies, each with the ids of the movies that have it. */
    private fun byTrait(movies: List<Movie>, traits: Map<String, Set<Trait>>, minimum: Int): Map<Trait, List<String>> =
        movies.flatMap { movie -> traits.getValue(movie.id).map { it to movie.id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= minimum }

    /** Of [ids] in their order, the ones of no series of a movie [taken] or of one picked before. */
    private fun distinctNames(ids: List<String>, names: Map<String, Set<String>>, taken: List<String>): List<String> {
        val picked = taken.toMutableList()
        return ids.filter { id -> picked.none { sameSeries(names.getValue(it), names.getValue(id)) }.also { if (it) picked += id } }
    }

    private fun namesOf(movies: List<Movie>): Map<String, Set<String>> = movies.associate { it.id to wordsOf(it.primaryTitle) }

    companion object {
        /** The actors of a movie that count as its stars: the top-billed. */
        private const val TOP_BILLED = 4
        private const val ODD_TRIES = 30
        private const val GRID_TRIES = 300

        /** The words too common in titles to tell a series by. */
        private val COMMON_WORDS = setOf("the", "and", "for", "from", "with", "how", "who", "what", "part", "its", "his", "her", "our", "your")

        /**
         * The words of a title that a series shares: in lower case, three letters
         * or more, the common ones left out, so "The Bourne Ultimatum" is
         * "bourne" and "ultimatum", and "X2: X-Men United" is "men" and "united".
         */
        fun wordsOf(title: String): Set<String> =
            title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in COMMON_WORDS }.toSet()

        /**
         * Whether two titles may be of one series: they share a word, as "Dune" and
         * "Dune: Part Two" do, or "Rise of the Planet of the Apes" and "Dawn of the
         * Planet of the Apes". Two movies of no series that share a word are kept
         * apart as well, which costs a puzzle nothing.
         */
        fun sameSeries(a: Set<String>, b: Set<String>): Boolean = a.any { it in b }
    }
}
