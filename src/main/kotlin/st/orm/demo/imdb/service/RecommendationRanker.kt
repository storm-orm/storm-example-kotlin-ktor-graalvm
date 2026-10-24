package st.orm.demo.imdb.service

import st.orm.Ref
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Reason
import st.orm.demo.imdb.model.ReasonKind
import st.orm.demo.imdb.repository.CandidateCredit
import st.orm.demo.imdb.repository.DIRECTOR
import st.orm.template.ref
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A movie of the user's own and how much it says about their taste: a
 * thumbs up weighs most, a thumbs down weighs against, a watchlist entry
 * ([wanted]) is an intention rather than an experience and weighs less.
 */
data class Seed(val movie: Movie, val weight: Double, val wanted: Boolean)

/**
 * What a user's lists say they like: a weight per genre, per person and
 * per decade, summed over the seeds, and which seeds brought each genre
 * and person in, so a pick can say which movie of the user's own argued
 * for it. A director weighs more than an actor on the same seed. The
 * rating range is where the ratings of what the user sees lie
 * ([RatingRange]), null while too few are seen to tell.
 */
data class TasteProfile(
    val seeds: List<Seed>,
    val genreWeights: Map<Ref<Genre>, Double>,
    val people: Map<Ref<Person>, Double>,
    val decadeWeights: Map<Int, Double>,
    val seedsByGenre: Map<Ref<Genre>, List<Seed>>,
    val seedsByPerson: Map<Ref<Person>, List<Seed>>,
    val ratingRange: RatingRange? = null
) {
    val isEmpty: Boolean get() = seeds.isEmpty()

    companion object {
        val EMPTY = TasteProfile(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Where the ratings of what the user sees lie: their mean and their spread,
 * the standard deviation, never narrower than [MIN_SPREAD] so that five
 * movies rated alike do not narrow it to a point. A candidate's rating
 * [fit]s the range by how many spreads it lies from the mean, the normal
 * curve: one for the mean itself, about a third two spreads out, and never
 * less than [FLOOR] of the score, so someone who rarely sees anything under
 * a 7 is still shown an outstanding 6, only further down the list.
 */
data class RatingRange(val mean: Double, val spread: Double) {

    /** The share of its score a movie rated [rating] keeps, in [FLOOR, 1]. */
    fun fit(rating: Double): Double {
        val spreads = (rating - mean) / spread
        return FLOOR + (1 - FLOOR) * exp(-spreads * spreads / 2)
    }

    companion object {
        /** How many seen movies it takes to tell where the user's ratings lie. */
        const val MIN_SEEN = 5
        const val MIN_SPREAD = 0.5
        const val FLOOR = 0.3

        /** The range of the seen ratings, as the ranker reads a rating ([RecommendationRanker.weightedRating]); null while fewer than [MIN_SEEN]. */
        fun of(seen: Collection<Rating>): RatingRange? {
            if (seen.size < MIN_SEEN) return null
            val ratings = seen.map { RecommendationRanker.weightedRating(it) }
            val mean = ratings.average()
            val spread = sqrt(ratings.sumOf { (it - mean) * (it - mean) } / ratings.size)
            return RatingRange(mean, max(spread, MIN_SPREAD))
        }
    }
}

/**
 * A movie that might be recommended, with what the scoring looks at: its
 * rating (which carries the movie), its genres, the credits of the user's
 * favourite people on it, how often the site's visitors viewed it lately,
 * how often the user was shown it already, and when it joined the
 * catalogue, if it joined after the catalogue was created.
 */
data class Candidate(
    val rating: Rating,
    val genres: List<Genre>,
    val credits: List<CandidateCredit>,
    val viewCount: Long,
    val shownCount: Int = 0,
    val addedAt: Instant? = null
) {
    val movie: Movie get() = rating.movie
}

/** A scored candidate: the pick, the seed that argued most for it, and the reasons the card shows. */
data class Pick(val movie: Movie, val score: Double, val seed: Seed?, val reasons: List<Reason>)

/** The decade a year falls in, as the year it starts: 1999 is 1990. */
fun decadeOf(year: Int): Int = year / 10 * 10

/**
 * The scoring, in one place and free of the database: the taste profile
 * and the candidates come in, the best picks come out. Seven terms add up,
 * each in [0, 1] (the three taste terms can go negative after a thumbs down):
 *
 * - genre affinity, the candidate's genres weighed by the profile;
 * - person affinity, the favourite people credited on it, weighed the
 *   same, a director counting more than an actor;
 * - decade affinity, how much of the profile falls in the candidate's decade;
 * - quality, the IMDB average shrunk towards the catalogue mean for movies
 *   with few votes, so a 9.0 from two thousand voters does not beat an 8.5
 *   from two million;
 * - recency, a mild preference for newer movies;
 * - trending, how much the site's visitors are viewing it this week;
 * - newness, a push for a movie that joined the catalogue lately, fading
 *   over the month after it joined.
 *
 * The sum is then scaled by how the candidate's rating fits the range the
 * user usually watches in ([RatingRange]): a movie rated like what they
 * see keeps its score, one rated far outside, below or above, keeps a
 * share of it. The affinities are normalised by the profile's strongest
 * weight, so a profile of three seeds and one of three hundred score on
 * the same scale. With an empty profile only the last four terms remain,
 * which is the list every new visitor gets. A pick already shown to the
 * user keeps a share of its score per impression, so the list turns over
 * between visits.
 */
object RecommendationRanker {

    const val LIMIT = 100

    private const val GENRE_WEIGHT = 1.0
    private const val PERSON_WEIGHT = 1.5
    private const val DECADE_WEIGHT = 0.5
    private const val QUALITY_WEIGHT = 1.0
    private const val RECENCY_WEIGHT = 0.2
    private const val TRENDING_WEIGHT = 0.3
    private const val NEWNESS_WEIGHT = 0.4

    /** The votes at which the IMDB average counts as much as the prior, and the prior itself. */
    private const val CONFIDENCE_VOTES = 25_000.0
    private const val PRIOR_RATING = 6.5

    /** Older than this, a movie earns no recency. */
    private const val RECENCY_YEARS = 40.0

    /** How long a movie counts as new after joining the catalogue; the push fades over it. */
    val NEW_WINDOW: Duration = Duration.ofDays(30)

    /** No more picks than this for the same favourite person: the list is not one filmography. */
    private const val PICKS_PER_PERSON = 3

    /** What a pick keeps of its score for each time it was shown and passed over: six times, about a third. */
    private const val IMPRESSION_DECAY = 0.85

    fun rank(profile: TasteProfile, candidates: Collection<Candidate>, now: Instant, limit: Int = LIMIT): List<Pick> {
        val currentYear = now.atZone(ZoneOffset.UTC).year
        val maxGenreWeight = profile.genreWeights.values.maxOfOrNull { it }?.takeIf { it > 0 } ?: 1.0
        val maxPersonWeight = profile.people.values.maxOfOrNull { it }?.takeIf { it > 0 } ?: 1.0
        val maxDecadeWeight = profile.decadeWeights.values.maxOfOrNull { it }?.takeIf { it > 0 } ?: 1.0
        val maxViewCount = candidates.maxOfOrNull { it.viewCount }?.takeIf { it > 0 } ?: 1L

        val scored = candidates.map { candidate ->
            val genres = candidate.genres.map { genre -> genre to (profile.genreWeights[genre.ref()] ?: 0.0) }
            val people = candidate.credits
                .distinctBy { it.person.id }
                .map { credit -> credit to (profile.people[credit.person.ref()] ?: 0.0) }
            val decade = candidate.movie.startYear?.let(::decadeOf)
            val genreAffinity = (genres.sumOf { it.second } / maxGenreWeight).coerceIn(-1.0, 1.0)
            val personAffinity = (people.sumOf { it.second } / maxPersonWeight).coerceIn(-1.0, 1.0)
            val decadeAffinity = ((decade?.let { profile.decadeWeights[it] } ?: 0.0) / maxDecadeWeight).coerceIn(-1.0, 1.0)
            val quality = ((weightedRating(candidate.rating) - 5.0) / 5.0).coerceIn(0.0, 1.0)
            val recency = candidate.movie.startYear?.let { (1.0 - (currentYear - it) / RECENCY_YEARS).coerceIn(0.0, 1.0) } ?: 0.0
            val trending = candidate.viewCount.toDouble() / maxViewCount
            val newness = candidate.addedAt?.let { newness(it, now) } ?: 0.0
            val fit = GENRE_WEIGHT * genreAffinity +
                PERSON_WEIGHT * personAffinity +
                DECADE_WEIGHT * decadeAffinity +
                QUALITY_WEIGHT * quality +
                RECENCY_WEIGHT * recency +
                TRENDING_WEIGHT * trending +
                NEWNESS_WEIGHT * newness
            // Rated outside the user's usual range, a share of the score is kept; shown and passed over,
            // each impression takes a share off, so other picks get their turn.
            val ratingFit = profile.ratingRange?.fit(weightedRating(candidate.rating)) ?: 1.0
            val score = if (fit > 0) fit * ratingFit * IMPRESSION_DECAY.pow(candidate.shownCount) else fit

            val bestPerson = people.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            val bestGenre = genres.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            val seed = seedFor(profile, bestPerson, genres.filter { it.second > 0 }.map { it.first })
            val reasons = reasons(candidate, seed, bestPerson, bestGenre, decade.takeIf { decadeAffinity >= 0.5 }, newness > 0, trending)
            Scored(Pick(candidate.movie, score, seed, reasons), bestPerson?.person?.ref())
        }

        val picksPerPerson = mutableMapOf<Ref<Person>, Int>()
        return scored
            .sortedWith(compareByDescending<Scored> { it.pick.score }.thenBy { it.pick.movie.id })
            .filter { scored ->
                val person = scored.person ?: return@filter true
                val count = picksPerPerson.getOrDefault(person, 0)
                (count < PICKS_PER_PERSON).also { kept -> if (kept) picksPerPerson[person] = count + 1 }
            }
            .take(limit)
            .map { it.pick }
    }

    private class Scored(val pick: Pick, val person: Ref<Person>?)

    /**
     * The IMDB average shrunk towards the prior in proportion to how few
     * votes it rests on: a Bayesian average, the usual way to rank by rating
     * without letting a handful of voters decide.
     */
    fun weightedRating(rating: Rating): Double {
        val votes = rating.voteCount.toDouble()
        return (votes / (votes + CONFIDENCE_VOTES)) * rating.averageRating.toDouble() +
            (CONFIDENCE_VOTES / (votes + CONFIDENCE_VOTES)) * PRIOR_RATING
    }

    /** One the moment a movie joins, nothing once the window has passed, straight in between. */
    fun newness(addedAt: Instant, now: Instant): Double =
        (1.0 - Duration.between(addedAt, now).toMillis().toDouble() / NEW_WINDOW.toMillis()).coerceIn(0.0, 1.0)

    /**
     * The seed that argued most for a candidate: the strongest seed that
     * shares the best-matched person, else the strongest seed among those
     * sharing the most of the candidate's liked genres.
     */
    private fun seedFor(profile: TasteProfile, bestPerson: CandidateCredit?, likedGenres: List<Genre>): Seed? {
        bestPerson?.let { credit ->
            profile.seedsByPerson[credit.person.ref()]?.filter { it.weight > 0 }?.maxByOrNull { it.weight }?.let { return it }
        }
        return likedGenres
            .flatMap { genre -> profile.seedsByGenre[genre.ref()].orEmpty() }
            .filter { it.weight > 0 }
            .groupBy { it }
            .maxWithOrNull(compareBy<Map.Entry<Seed, List<Seed>>> { it.value.size }.thenBy { it.key.weight })
            ?.key
    }

    /** The reasons the card shows, at most three, the most personal first. */
    private fun reasons(
        candidate: Candidate,
        seed: Seed?,
        bestPerson: CandidateCredit?,
        bestGenre: Genre?,
        likedDecade: Int?,
        isNew: Boolean,
        trending: Double
    ): List<Reason> =
        buildList {
            seed?.let {
                add(Reason(ReasonKind.SEED, if (it.wanted) "Because ${it.movie.primaryTitle} is on your watchlist" else "Because you saw ${it.movie.primaryTitle}"))
            }
            bestPerson?.let { add(Reason(ReasonKind.PERSON, creditText(it))) }
            if (bestPerson == null && bestGenre != null) {
                add(Reason(ReasonKind.GENRE, "${bestGenre.name}, a genre you like"))
            }
            if (isNew) {
                add(Reason(ReasonKind.NEW, "New in the catalogue"))
            }
            if (size < 3 && likedDecade != null) {
                add(Reason(ReasonKind.DECADE, "From the ${likedDecade}s, like much of what you've seen"))
            }
            if (trending >= 0.5) {
                add(Reason(ReasonKind.TRENDING, "Popular on Storm Movies this week"))
            }
            if (size < 2 && weightedRating(candidate.rating) >= 8.0) {
                add(Reason(ReasonKind.RATING, "Rated ${candidate.rating.averageRating.setScale(1, RoundingMode.HALF_UP)} by ${votes(candidate.rating.voteCount)} voters"))
            }
        }.take(3)

    private fun creditText(credit: CandidateCredit): String = when (credit.category) {
        DIRECTOR -> "Directed by ${credit.person.primaryName}"
        "writer" -> "Written by ${credit.person.primaryName}"
        else -> "Stars ${credit.person.primaryName}"
    }

    private fun votes(voteCount: Int): String = when {
        voteCount >= 1_000_000 -> "${BigDecimal(voteCount).divide(BigDecimal(1_000_000)).setScale(1, RoundingMode.HALF_UP)}M"
        voteCount >= 1_000 -> "${voteCount / 1_000}K"
        else -> voteCount.toString()
    }
}
