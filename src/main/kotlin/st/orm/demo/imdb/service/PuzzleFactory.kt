package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.DIRECTOR
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.template.entityId
import st.orm.template.ref
import kotlin.random.Random

/**
 * Something movies have in common: the key that tells it apart ("dir:nm0634240"),
 * how the reveal says it ("Directed by Christopher Nolan"), and how hard it is to
 * spot, from 1, the easiest, to 4.
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
 * Makes the daily puzzles out of what the catalogue knows of its movies:
 * who directed them, who starred, the year, the country and the language,
 * a few narrow genres, and the runtime. Every puzzle has one answer only:
 * an Odd One Out's four movies have no other three in common, and a
 * Connections grid's groups share nothing with one another's movies.
 */
class PuzzleFactory(
    private val principalRepository: PrincipalRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val movieCountryRepository: MovieCountryRepository,
    private val movieLanguageRepository: MovieLanguageRepository
) {

    /**
     * What each of the movies has, by movie id, read in a query per kind for
     * all of them: the directors, the top-billed actors, the year, the
     * countries but the United States, the languages but English, which too
     * many share to make a puzzle, the narrow genres, and a long or short runtime.
     */
    fun traitsOf(movies: List<Movie>): Map<String, Set<Trait>> {
        val refs = movies.map { it.ref() }
        val traits = movies.associate { it.id to mutableSetOf<Trait>() }
        principalRepository.findTasteCredits(refs, TOP_BILLED).forEach { credit ->
            val person = credit.person
            val trait = when (credit.category) {
                DIRECTOR -> Trait("dir:${person.id}", "Directed by ${person.primaryName}", 1)
                in ACTING_CATEGORIES -> Trait("act:${person.id}", "With ${person.primaryName}", 2)
                else -> null
            }
            trait?.let { traits[credit.movie.entityId()]?.add(it) }
        }
        movies.forEach { movie ->
            val own = traits.getValue(movie.id)
            movie.startYear?.let { own += Trait("year:$it", "From $it", 3) }
            movie.runtimeMinutes?.let {
                if (it >= LONG_MINUTES) own += Trait("long", "Over 2½ hours", 4)
                if (it <= SHORT_MINUTES) own += Trait("short", "Under 90 minutes", 4)
            }
        }
        movieCountryRepository.findByMovies(refs).filter { it.id.country != "US" }.forEach {
            traits[it.movie.entityId()]?.add(Trait("country:${it.id.country}", "Made in ${Countries.of(it.id.country)?.name ?: it.id.country}", 3))
        }
        movieLanguageRepository.findByMovies(refs).filter { it.id.language != "en" }.forEach {
            traits[it.movie.entityId()]?.add(Trait("lang:${it.id.language}", "In ${Languages.nameOf(it.id.language)}", 3))
        }
        movieGenreRepository.findByMovies(movies).filter { it.genre.name in NARROW_GENRES }.forEach {
            traits[it.movie.id]?.add(Trait("genre:${it.genre.name}", it.genre.name, 4))
        }
        return traits
    }

    /**
     * A day's Odd One Out: [count] puzzles, the easier first, no movie in two
     * of them. Each is three movies that share a trait and one that does not,
     * which no other three of the four share anything with.
     */
    fun oddOneOut(movies: List<Movie>, traits: Map<String, Set<Trait>>, random: Random, count: Int): List<OddPuzzle> {
        val used = mutableSetOf<String>()
        return (0 until count).mapNotNull { index ->
            // Directors and actors first, then years and places, the genres and runtimes last.
            val levels = when {
                index < count * 3 / 10 -> 1..2
                index < count * 7 / 10 -> 2..3
                else -> 3..4
            }
            oddPuzzle(movies.filter { it.id !in used }, traits, random, levels)?.also { used += it.movies }
        }
    }

    private fun oddPuzzle(movies: List<Movie>, traits: Map<String, Set<Trait>>, random: Random, levels: IntRange): OddPuzzle? {
        val byTrait = byTrait(movies, traits, minimum = 3)
        for (trait in byTrait.keys.filter { it.level in levels }.shuffled(random)) {
            val three = byTrait.getValue(trait).shuffled(random).take(3)
            val others = movies.filter { trait !in traits.getValue(it.id) }.shuffled(random).take(ODD_TRIES)
            for (odd in others) {
                val four = three + odd.id
                // One answer only: no trait but this one is shared by three of the four.
                val shared = four.flatMap { traits.getValue(it) }.groupingBy { it }.eachCount().filterValues { it >= 3 }.keys
                if (shared == setOf(trait)) return OddPuzzle(four.shuffled(random), odd.id, trait)
            }
        }
        return null
    }

    /**
     * A day's Movie Connections: four groups of four, one of each level where
     * it can, every group's trait had by its own four of the sixteen alone,
     * so the grid has one way to solve it. Null when none was found.
     */
    fun connections(movies: List<Movie>, traits: Map<String, Set<Trait>>, random: Random): List<ConnectionGroup>? {
        val byTrait = byTrait(movies, traits, minimum = 4)
        repeat(GRID_TRIES) {
            val chosen = (1..4).mapNotNull { level -> byTrait.keys.filter { it.level == level }.randomOrNull(random) }
                .let { if (it.size == 4) it else byTrait.keys.shuffled(random).take(4) }
            if (chosen.size < 4 || chosen.map { it.key }.toSet().size < 4) return@repeat
            val taken = mutableSetOf<String>()
            val groups = chosen.map { trait ->
                val four = byTrait.getValue(trait)
                    .filter { it !in taken && chosen.none { other -> other != trait && other in traits.getValue(it) } }
                    .shuffled(random).take(4)
                taken += four
                ConnectionGroup(four, trait)
            }
            if (groups.any { it.movies.size < 4 }) return@repeat
            // One way to solve it: of the sixteen, only a group's own four have its trait.
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

    companion object {
        /** The actors of a movie that count as its stars: the top-billed. */
        private const val TOP_BILLED = 4
        private const val LONG_MINUTES = 150
        private const val SHORT_MINUTES = 90
        /** The genres narrow enough to make a group: Drama or Comedy would take in half the posters. */
        val NARROW_GENRES = setOf("Animation", "Western", "Musical", "Horror", "War", "Film-Noir", "Sport", "Music", "Biography", "Documentary")
        private const val ODD_TRIES = 30
        private const val GRID_TRIES = 300
    }
}
