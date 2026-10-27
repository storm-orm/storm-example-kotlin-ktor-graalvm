package st.orm.demo.imdb.service

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.COMPOSER
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

/** A Face/Off: two movies, and the one IMDb rates higher. */
@Serializable
data class DuelPuzzle(val movies: List<String>, val higher: String)

/** A line-up of The Usual Suspects: a movie, a part in it, five people, and the one who played the part. */
@Serializable
data class LineUpPuzzle(val movie: String, val character: String, val suspects: List<String>, val culprit: String)

/** A Memento series: its movies as shown, shuffled, and in the order they came out. */
@Serializable
data class SeriesPuzzle(val movies: List<String>, val order: List<String>)

/** An Amadeus puzzle: a movie, four composers, and the one who wrote its score. */
@Serializable
data class ScorePuzzle(val movie: String, val composers: List<String>, val composer: String)

/**
 * Makes the daily puzzles out of who made the catalogue's movies: the
 * director and the top-billed actors, and for Amadeus the composer. Every
 * puzzle has one answer only: an Odd One Out's four movies have no other
 * three in common, and a Connections grid's groups share no one with one
 * another's movies. A puzzle never holds two movies whose titles share a
 * word, such as a movie and its sequel, which would give the answer away;
 * a Memento series is made of just those.
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

    /** Everyone credited on the movies, cast and crew, read in one query: the line-ups and the scores are made of them. */
    fun creditsOf(movies: List<Movie>): List<Principal> = principalRepository.findByMovies(movies.map { it.ref() })

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

    /**
     * A day's Face/Off: [count] pairs of movies, no movie in two of them and
     * none with its sequel, each pair's ratings at least [DUEL_GAP] apart, so
     * the higher one is a fair question.
     */
    fun faceOffs(ratings: List<Rating>, random: Random, count: Int): List<DuelPuzzle> {
        val names = namesOf(ratings.map { it.movie })
        val byId = ratings.associateBy { it.movie.id }
        val left = ratings.map { it.movie.id }.shuffled(random).toMutableList()
        val duels = mutableListOf<DuelPuzzle>()
        while (duels.size < count && left.isNotEmpty()) {
            val first = left.removeFirst()
            val second = left.firstOrNull { other ->
                (byId.getValue(first).averageRating - byId.getValue(other).averageRating).abs() >= DUEL_GAP &&
                    !sameSeries(names.getValue(first), names.getValue(other))
            } ?: continue
            left -= second
            val higher = if (byId.getValue(first).averageRating > byId.getValue(second).averageRating) first else second
            duels += DuelPuzzle(listOf(first, second), higher)
        }
        return duels
    }

    /**
     * A day's line-ups of The Usual Suspects: [count], no movie in two of
     * them. Each is a top-billed part of a movie, the one who played it, and
     * four others of the same category from other movies' casts who are not
     * in this one.
     */
    fun lineUps(movies: List<Movie>, credits: List<Principal>, random: Random, count: Int): List<LineUpPuzzle> {
        val cast = credits.filter { it.category in ACTING_CATEGORIES }.groupBy { it.movie.entityId() }
        val lineUps = mutableListOf<LineUpPuzzle>()
        for (movie in movies.shuffled(random)) {
            if (lineUps.size == count) break
            val inMovie = cast[movie.id].orEmpty()
            val parts = inMovie.filter { it.ordering <= TOP_BILLED && usablePart(it) }
            val culprit = parts.randomOrNull(random) ?: continue
            val present = inMovie.map { it.person.id }.toSet()
            val others = cast.values.flatten()
                .filter { it.category == culprit.category && it.person.id !in present && it.ordering <= TOP_BILLED }
                .distinctBy { it.person.id }.shuffled(random).take(SUSPECTS - 1)
            if (others.size < SUSPECTS - 1) continue
            val suspects = (others.map { it.person.id } + culprit.person.id).shuffled(random)
            lineUps += LineUpPuzzle(movie.id, culprit.characters!!, suspects, culprit.person.id)
        }
        return lineUps
    }

    /**
     * A day's Memento: up to [count] series, each the movies of a series that
     * are in the pool, [SERIES_SIZE] at most, the best known of them, from at
     * least [SERIES_MINIMUM] years. A series is movies whose titles share a
     * word, as "Rocky" and "Rocky II" do, with a top-billed actor in the
     * same part in both, so "American Psycho" and "American Hustle", which
     * share Christian Bale but not his part, stay apart.
     */
    fun series(movies: List<Movie>, credits: List<Principal>, random: Random, count: Int): List<SeriesPuzzle> {
        val names = movies.associate { it.id to seriesWordsOf(it.primaryTitle) }
        val parts = credits.filter { it.category in ACTING_CATEGORIES && it.ordering <= TOP_BILLED && it.characters != null }
            .groupBy({ it.movie.entityId() }, { "${it.person.id}:${it.characters}" }).mapValues { it.value.toSet() }
        // Each movie's series: itself to begin with, joined with every movie of its series.
        val seriesOf = movies.associate { it.id to it.id }.toMutableMap()
        fun rootOf(id: String): String = seriesOf.getValue(id).let { if (it == id) id else rootOf(it).also { root -> seriesOf[id] = root } }
        for ((i, a) in movies.withIndex()) for (b in movies.subList(i + 1, movies.size)) {
            val related = names.getValue(a.id).any { it in names.getValue(b.id) } && parts[a.id].orEmpty().any { it in parts[b.id].orEmpty() }
            if (related) seriesOf[rootOf(a.id)] = rootOf(b.id)
        }
        val byId = movies.associateBy { it.id }
        return movies.groupBy { rootOf(it.id) }.values
            .map { series -> series.distinctBy { it.startYear }.sortedBy { it.startYear } }
            .filter { it.size >= SERIES_MINIMUM }
            .shuffled(random).take(count)
            .map { series ->
                // The best known of a long series, in the pool's order, then by the year.
                val chosen = series.sortedBy { movies.indexOf(it) }.take(SERIES_SIZE).sortedBy { it.startYear }
                SeriesPuzzle(chosen.map { it.id }.shuffled(random), chosen.map { it.id })
            }
            .also { it.forEach { series -> series.order.forEach(byId::getValue) } }
    }

    /**
     * A day's Amadeus: [count] movies of the player's own that have a composer,
     * each with three other composers of the pool to choose from, the ones
     * with the most movies in it most often, as the names one may know.
     */
    fun scores(own: List<Movie>, credits: List<Principal>, random: Random, count: Int): List<ScorePuzzle> {
        val composers = credits.filter { it.category == COMPOSER }
        val byMovie = composers.groupBy { it.movie.entityId() }
        // The composers of the pool, each as often as they scored a movie in it: the well-known come up more.
        val known = composers.map { it.person.id }
        if (known.distinct().size < CHOICES) return emptyList()
        return own.shuffled(random).mapNotNull { movie ->
            val composer = byMovie[movie.id]?.minByOrNull { it.ordering } ?: return@mapNotNull null
            val credited = byMovie.getValue(movie.id).map { it.person.id }.toSet()
            val others = generateSequence { known.random(random) }.filter { it !in credited }.distinct().take(CHOICES - 1).toList()
            ScorePuzzle(movie.id, (others + composer.person.id).shuffled(random), composer.person.id)
        }.take(count)
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
        /** The least two ratings are apart in a Face/Off, so the higher one is not a coin toss. */
        private val DUEL_GAP = java.math.BigDecimal("0.3")
        /** The people in a line-up. */
        const val SUSPECTS = 5
        /** The composers to choose from. */
        const val CHOICES = 4
        /** The movies of a Memento series: three at least, five at most. */
        const val SERIES_MINIMUM = 3
        const val SERIES_SIZE = 5

        /** The words too common in titles to tell a series by. */
        private val COMMON_WORDS = setOf("the", "and", "for", "from", "with", "how", "who", "what", "part", "its", "his", "her", "our", "your")

        /** The parts no line-up asks about: people as themselves, voices, and parts named in the credits' own way. */
        private val NO_PART = Regex("^(self|himself|herself|narrator|various|additional voices)$", RegexOption.IGNORE_CASE)

        /**
         * The words of a title that a series shares: in lower case, three letters
         * or more, the common ones left out, so "The Bourne Ultimatum" is
         * "bourne" and "ultimatum", and "X2: X-Men United" is "men" and "united".
         */
        fun wordsOf(title: String): Set<String> =
            title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in COMMON_WORDS }.toSet()

        /** The words a Memento series is told by: the title's, four letters or more, so "Man" alone joins nothing. */
        fun seriesWordsOf(title: String): Set<String> = wordsOf(title).filter { it.length >= 4 }.toSet()

        /**
         * Whether two titles may be of one series: they share a word, as "Dune" and
         * "Dune: Part Two" do, or "Rise of the Planet of the Apes" and "Dawn of the
         * Planet of the Apes". Two movies of no series that share a word are kept
         * apart as well, which costs a puzzle nothing.
         */
        fun sameSeries(a: Set<String>, b: Set<String>): Boolean = a.any { it in b }

        /** Whether a credit names a part a line-up can ask about: a character, not the actor as themselves. */
        fun usablePart(credit: Principal): Boolean {
            val part = credit.characters?.trim() ?: return false
            return part.length >= 2 && !NO_PART.matches(part) && '(' !in part &&
                // A part named after the actor, as in Being John Malkovich, gives the answer away.
                credit.person.primaryName.split(' ').none { it.length >= 3 && part.contains(it, ignoreCase = true) }
        }
    }
}
