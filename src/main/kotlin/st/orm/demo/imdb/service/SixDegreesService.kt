package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.template.entityId
import st.orm.template.refById
import st.orm.template.transaction

/** One step of a chain: the movie two actors played in, and the actor it leads to. */
data class Link(val movie: Movie, val person: Person)

/**
 * How two actors connect: the chain of movies from [from] to [to], each
 * leading to someone who played in it, or null when none was found among
 * the well-known movies within six steps.
 */
data class Connection(val from: Person, val to: Person, val links: List<Link>?) {
    val found: Boolean get() = links != null
    val degrees: Int get() = links?.size ?: 0
}

/**
 * Six degrees: the shortest chain of shared movies between two actors. The
 * graph is the acting credits of the movies at least [MINIMUM_VOTES] voted
 * on, read as it is searched: a breadth-first search from both ends at
 * once, each step two queries, from the people reached to their movies and
 * from those movies to their casts, always from the end that has reached
 * fewer people. The two searches meet in the middle, so neither goes deeper
 * than three steps for a chain of six.
 */
class SixDegreesService(
    private val principalRepository: PrincipalRepository,
    private val personRepository: PersonRepository,
    private val movieRepository: MovieRepository
) {

    /** How well known a movie must be to be part of a chain, for the page to say. */
    val minimumVotes: Int get() = MINIMUM_VOTES

    /** A person to start from, picked already: the page's first box, filled in. */
    suspend fun person(id: String): Person? = transaction(readOnly = true) { personRepository.findById(id) }

    /** How the two connect, or null when either is not a known person. */
    suspend fun connect(fromId: String, toId: String): Connection? = transaction(readOnly = true) {
        val from = personRepository.findById(fromId) ?: return@transaction null
        val to = personRepository.findById(toId) ?: return@transaction null
        val chain = if (from.id == to.id) emptyList() else search(from.id, to.id)
        Connection(from, to, chain?.let(::links))
    }

    /** Two actors everybody knows, the leads of the most voted movies, picked at random, for "Surprise me". */
    suspend fun randomPair(): Pair<String, String> = transaction(readOnly = true) {
        val leads = principalRepository.findLeadLinks(SURPRISE_VOTES)
        val byMovie = leads.groupBy({ it.movie.entityId() }, { it.person.entityId() })
        val people = leads.map { it.person.entityId() }.distinct().shuffled()
        val first = people.first()
        // Not two from one movie: that is one step, no game.
        val costars = byMovie.values.filter { first in it }.flatten().toSet()
        first to people.first { it !in costars }
    }

    /** One end of the search: who it reached, through which movie and from whom, and who it reached last. */
    private class Side(start: String) {
        val reached = hashMapOf<String, Step?>(start to null)
        val movies = hashSetOf<String>()
        var frontier = listOf(start)
    }

    /** How a person was reached: the movie, and the person before them in it. */
    private data class Step(val movie: String, val previous: String)

    /**
     * The chain between the two, as the people and the movies between them
     * in turn, or null when the ends do not meet within six steps or before
     * the search grows too large.
     */
    private fun search(fromId: String, toId: String): List<String>? {
        val start = Side(fromId)
        val end = Side(toId)
        repeat(MAX_DEGREES) {
            val side = if (start.frontier.size <= end.frontier.size) start else end
            val other = if (side === start) end else start
            // Of the people where the searches meet, the one the other end reached soonest makes the shortest chain.
            expand(side, other).minByOrNull { chainOf(other, it).size }?.let { meeting ->
                return chainOf(start, meeting).reversed() + chainOf(end, meeting).drop(1)
            }
            if (side.frontier.isEmpty() || start.reached.size + end.reached.size > MAX_PEOPLE) return null
        }
        return null
    }

    /**
     * One step out from [side]: its last reached people's movies, then those
     * movies' casts, each person reached once. Returns the people [other]
     * reached already, where the two searches meet.
     */
    private fun expand(side: Side, other: Side): List<String> {
        val credits = side.frontier.chunked(CHUNK).flatMap { people -> principalRepository.findActingLinks(people.map { refById<Person>(it) }, MINIMUM_VOTES) }
        // The first person to reach a movie is the one the chain goes through.
        val via = linkedMapOf<String, String>()
        credits.forEach { credit ->
            val movie = credit.movie.entityId()
            if (movie !in side.movies) via.putIfAbsent(movie, credit.person.entityId())
        }
        side.movies += via.keys
        val cast = via.keys.chunked(CHUNK).flatMap { movies -> principalRepository.findCastLinks(movies.map { refById<Movie>(it) }) }
        val next = mutableListOf<String>()
        cast.forEach { credit ->
            val person = credit.person.entityId()
            if (person !in side.reached) {
                val movie = credit.movie.entityId()
                side.reached[person] = Step(movie, via.getValue(movie))
                next += person
            }
        }
        side.frontier = next
        return next.filter { it in other.reached }
    }

    /** The chain from [person] back to the side's start: the person, the movie, the person before, and so on. */
    private fun chainOf(side: Side, person: String): List<String> {
        val chain = mutableListOf(person)
        var step = side.reached[person]
        while (step != null) {
            chain += step.movie
            chain += step.previous
            step = side.reached[step.previous]
        }
        return chain
    }

    /** The chain's ids as links: people at the even places, movies at the odd ones, all read in two queries. */
    private fun links(chain: List<String>): List<Link> {
        val people = personRepository.findAllById(chain.filterIndexed { i, _ -> i % 2 == 0 }).associateBy { it.id }
        val movies = movieRepository.findAllById(chain.filterIndexed { i, _ -> i % 2 == 1 }).associateBy { it.id }
        return (1 until chain.size step 2).map { i -> Link(movies.getValue(chain[i]), people.getValue(chain[i + 1])) }
    }

    companion object {
        /** The movies the chains go through: known enough to make sense of one. */
        const val MINIMUM_VOTES = 5000
        private const val MAX_DEGREES = 6
        /** The search gives up once both ends together reached this many people. */
        private const val MAX_PEOPLE = 250_000
        /** How many ids a query takes at once, well within what a statement may bind. */
        private const val CHUNK = 1000
        private const val SURPRISE_VOTES = 400_000
    }
}
