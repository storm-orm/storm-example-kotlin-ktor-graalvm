package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.ReasonKind
import st.orm.demo.imdb.repository.CandidateCredit
import st.orm.demo.imdb.service.Candidate
import st.orm.demo.imdb.service.RatingRange
import st.orm.demo.imdb.service.RecommendationRanker
import st.orm.demo.imdb.service.Seed
import st.orm.demo.imdb.service.TasteProfile
import st.orm.demo.imdb.service.decadeOf
import st.orm.template.ref
import java.math.BigDecimal
import java.time.Instant

/**
 * The scoring, free of the database: a taste profile and candidates built
 * by hand go in, and the order, the reasons and the seeds that come out are
 * checked. The weights are the ranker's own; the tests pin the behaviour,
 * not the numbers.
 */
class RecommendationRankerTest {

    private val sciFi = Genre(1, "Sci-Fi")

    @Test
    fun `a movie that joined the catalogue lately gets a push that fades over the month, and says so`() {
        val arrived = candidate(movie("tt0120382", "The Truman Show", 1998), 8.2, 1_200_000, genres = listOf(drama))
        val justArrived = arrived.copy(addedAt = NOW.minus(java.time.Duration.ofDays(1)))
        val arrivedLongAgo = arrived.copy(addedAt = NOW.minus(java.time.Duration.ofDays(45)))
        val settled = candidate(movie("tt0117951", "Trainspotting", 1996), 8.1, 750_000, genres = listOf(drama))

        assertEquals("The Truman Show", RecommendationRanker.rank(TasteProfile.EMPTY, listOf(justArrived, settled), NOW).first().movie.primaryTitle)
        assertEquals(listOf(ReasonKind.NEW, ReasonKind.RATING), RecommendationRanker.rank(TasteProfile.EMPTY, listOf(justArrived), NOW).single().reasons.map { it.kind })
        assertFalse(RecommendationRanker.rank(TasteProfile.EMPTY, listOf(arrivedLongAgo), NOW).single().reasons.any { it.kind == ReasonKind.NEW })
        assertEquals(RecommendationRanker.rank(TasteProfile.EMPTY, listOf(arrived), NOW).single().score, RecommendationRanker.rank(TasteProfile.EMPTY, listOf(arrivedLongAgo), NOW).single().score, 1e-9)
    }
    private val drama = Genre(2, "Drama")

    /** The moment the ranking runs at: the recency, the newness and the current year follow from it. */
    private val NOW: Instant = Instant.parse("2026-10-23T12:00:00Z")
    private val keanu = Person("nm0000206", "Keanu Reeves", 1964, null)
    private val nolan = Person("nm0634240", "Christopher Nolan", 1970, null)

    private val matrix = movie("tt0133093", "The Matrix", 1999)
    private val reloaded = movie("tt0234215", "The Matrix Reloaded", 2003)
    private val johnWick = movie("tt2911666", "John Wick", 2014)
    private val inception = movie("tt1375666", "Inception", 2010)
    private val shawshank = movie("tt0111161", "The Shawshank Redemption", 1994)
    private val obscure = movie("tt9999999", "Obscure Gem", 2021)

    @Test
    fun `a favourite person and a liked genre outrank a better-rated movie that matches neither`() {
        val profile = profileFrom(Seed(matrix, 1.0, wanted = false), genres = mapOf(matrix to listOf(sciFi)), people = mapOf(matrix to listOf(keanu to "actor")))
        val candidates = listOf(
            candidate(reloaded, 7.2, 600_000, genres = listOf(sciFi), credits = listOf(credit(keanu, "actor", reloaded, 7.2, 600_000))),
            candidate(shawshank, 9.3, 2_900_000, genres = listOf(drama))
        )

        val picks = RecommendationRanker.rank(profile, candidates, now = NOW)

        assertEquals(listOf("The Matrix Reloaded", "The Shawshank Redemption"), picks.map { it.movie.primaryTitle })
        val reloadedPick = picks.first()
        assertEquals(matrix, reloadedPick.seed?.movie)
        assertEquals(listOf(ReasonKind.SEED, ReasonKind.PERSON), reloadedPick.reasons.map { it.kind })
        assertEquals("Because you saw The Matrix", reloadedPick.reasons[0].text)
        assertEquals("Stars Keanu Reeves", reloadedPick.reasons[1].text)
        // Shawshank shares no person or genre with the profile: no seed, only its decade and its rating speak.
        assertNull(picks.last().seed)
        assertEquals(listOf(ReasonKind.DECADE, ReasonKind.RATING), picks.last().reasons.map { it.kind })
        assertEquals("From the 1990s, like much of what you've watched", picks.last().reasons[0].text)
        assertEquals("Rated 9.3 by 2.9M voters", picks.last().reasons[1].text)
    }

    @Test
    fun `a thumbs down weighs its people and genres against`() {
        val liked = profileFrom(Seed(matrix, 1.0, wanted = false), genres = mapOf(matrix to listOf(sciFi)), people = mapOf(matrix to listOf(keanu to "actor")))
        val disliked = profileFrom(Seed(matrix, -0.8, wanted = false), genres = mapOf(matrix to listOf(sciFi)), people = mapOf(matrix to listOf(keanu to "actor")))
        val candidates = listOf(
            candidate(johnWick, 7.4, 700_000, genres = listOf(sciFi), credits = listOf(credit(keanu, "actor", johnWick, 7.4, 700_000))),
            candidate(shawshank, 9.3, 2_900_000, genres = listOf(drama))
        )

        val whenLiked = RecommendationRanker.rank(liked, candidates, now = NOW)
        val whenDisliked = RecommendationRanker.rank(disliked, candidates, now = NOW)

        assertEquals("John Wick", whenLiked.first().movie.primaryTitle)
        assertEquals("The Shawshank Redemption", whenDisliked.first().movie.primaryTitle)
        // A disliked seed is no reason: the John Wick pick names neither the seed nor the actor.
        val johnWickPick = whenDisliked.first { it.movie == johnWick }
        assertNull(johnWickPick.seed)
        assertFalse(johnWickPick.reasons.any { it.kind == ReasonKind.PERSON || it.kind == ReasonKind.SEED })
    }

    @Test
    fun `the rating is shrunk towards the prior for movies with few votes`() {
        val candidates = listOf(
            candidate(obscure, 9.5, 1_500),
            candidate(inception, 8.8, 2_500_000)
        )

        val picks = RecommendationRanker.rank(TasteProfile.EMPTY, candidates, now = NOW)

        assertEquals("Inception", picks.first().movie.primaryTitle)
        assertTrue(RecommendationRanker.weightedRating(candidates[0].rating) < 7.0)
        assertTrue(RecommendationRanker.weightedRating(candidates[1].rating) > 8.7)
    }

    @Test
    fun `no more than three picks per favourite person`() {
        val profile = profileFrom(Seed(matrix, 1.0, wanted = false), genres = emptyMap(), people = mapOf(matrix to listOf(keanu to "actor")))
        val keanuMovies = (1..5).map { movie("tt000000$it", "Keanu Movie $it", 2000 + it) }
        val candidates = keanuMovies.map { candidate(it, 7.0, 100_000, credits = listOf(credit(keanu, "actor", it, 7.0, 100_000))) } +
            candidate(shawshank, 9.3, 2_900_000, genres = listOf(drama))

        val picks = RecommendationRanker.rank(profile, candidates, now = NOW)

        assertEquals(4, picks.size)
        assertEquals(3, picks.count { it.movie.primaryTitle.startsWith("Keanu Movie") })
    }

    @Test
    fun `an empty profile ranks by quality, recency and trending, with their reasons`() {
        val candidates = listOf(
            candidate(shawshank, 9.3, 2_900_000, genres = listOf(drama), viewCount = 0),
            candidate(inception, 8.8, 2_500_000, genres = listOf(sciFi), viewCount = 40)
        )

        val picks = RecommendationRanker.rank(TasteProfile.EMPTY, candidates, now = NOW)

        // Inception is newer and trending; that beats Shawshank's half-point of rating.
        assertEquals(listOf("Inception", "The Shawshank Redemption"), picks.map { it.movie.primaryTitle })
        assertEquals(listOf(ReasonKind.TRENDING, ReasonKind.RATING), picks.first().reasons.map { it.kind })
        assertTrue(picks.all { it.seed == null })
    }

    @Test
    fun `a pick shown and passed over fades, so a fresh one of the same fit overtakes it`() {
        val profile = profileFrom(Seed(matrix, 1.0, wanted = false), genres = mapOf(matrix to listOf(sciFi)), people = emptyMap())
        val shownOften = candidate(reloaded, 7.2, 600_000, genres = listOf(sciFi)).copy(shownCount = 6)
        val fresh = candidate(movie("tt0113277", "Heat", 1995), 8.3, 700_000, genres = listOf(drama))
        val unshownReloaded = shownOften.copy(shownCount = 0)

        assertEquals("The Matrix Reloaded", RecommendationRanker.rank(profile, listOf(unshownReloaded, fresh), NOW).first().movie.primaryTitle)
        assertEquals("Heat", RecommendationRanker.rank(profile, listOf(shownOften, fresh), NOW).first().movie.primaryTitle)
        // Six impressions keep about a third of the score; a negative fit is left alone.
        val faded = RecommendationRanker.rank(profile, listOf(shownOften), NOW).single().score
        val whole = RecommendationRanker.rank(profile, listOf(unshownReloaded), NOW).single().score
        assertEquals(whole * 0.377, faded, 0.01)
    }

    @Test
    fun `a watchlist seed is named as one`() {
        val profile = profileFrom(Seed(inception, 0.3, wanted = true), genres = emptyMap(), people = mapOf(inception to listOf(nolan to "director")))
        val interstellar = movie("tt0816692", "Interstellar", 2014)
        val candidates = listOf(candidate(interstellar, 8.7, 2_000_000, credits = listOf(credit(nolan, "director", interstellar, 8.7, 2_000_000))))

        val pick = RecommendationRanker.rank(profile, candidates, now = NOW).single()

        assertEquals("Because Inception is on your watchlist", pick.reasons[0].text)
        assertEquals("Directed by Christopher Nolan", pick.reasons[1].text)
    }

    /* ── Builders ─────────────────────────────────────────────────── */

    private fun movie(id: String, title: String, year: Int) = Movie(id, title, title, year, 120)

    private fun rating(movie: Movie, average: Double, votes: Int) = Rating(movie, BigDecimal.valueOf(average), votes)

    private fun credit(person: Person, category: String, movie: Movie, average: Double, votes: Int) =
        CandidateCredit(person, category, rating(movie, average, votes))

    private fun candidate(movie: Movie, average: Double, votes: Int, genres: List<Genre> = emptyList(), credits: List<CandidateCredit> = emptyList(), viewCount: Long = 0) =
        Candidate(rating(movie, average, votes), genres, credits, viewCount)

    @Test
    fun `the decade of the seeds counts, and is named when nothing more personal is`() {
        // Two Sci-Fi seeds from the 1990s; the candidates share the genre but one is from the 1990s and one from the 2010s.
        val profile = profileFrom(
            Seed(matrix, 1.0, wanted = false), Seed(movie("tt0120382", "The Truman Show", 1998), 1.0, wanted = false),
            genres = mapOf(matrix to listOf(sciFi)), people = emptyMap()
        )
        val gattaca = movie("tt0119177", "Gattaca", 1997)
        val arrival = movie("tt2543164", "Arrival", 2016)
        val candidates = listOf(
            candidate(gattaca, 7.7, 300_000, genres = listOf(sciFi)),
            candidate(arrival, 7.9, 800_000, genres = listOf(sciFi))
        )

        val picks = RecommendationRanker.rank(profile, candidates, now = NOW)

        // Arrival is newer and better rated; the decade turns it around for Gattaca.
        assertEquals(listOf("Gattaca", "Arrival"), picks.map { it.movie.primaryTitle })
        assertEquals(
            listOf("Because you saw The Matrix", "Sci-Fi, a genre you like", "From the 1990s, like much of what you've watched"),
            picks.first().reasons.map { it.text }
        )
        assertFalse(picks.last().reasons.any { it.kind == ReasonKind.DECADE })
    }

    @Test
    fun `the rating range is the mean and spread of the seen ratings, once five are seen`() {
        assertNull(RatingRange.of(listOf(8.7, 9.3, 8.9, 9.2).map { rating(matrix, it, 2_000_000) }))
        val range = RatingRange.of(listOf(7.0, 7.5, 8.0, 8.5, 9.0).map { rating(matrix, it, 2_000_000) })!!
        assertEquals(8.0, range.mean, 1e-9)
        assertEquals(0.707, range.spread, 0.001)
        // Five movies rated alike: the spread stays at its minimum, not a point.
        assertEquals(RatingRange.MIN_SPREAD, RatingRange.of(List(5) { rating(matrix, 8.0, 2_000_000) })!!.spread)
        // A movie at the mean keeps its whole score, two spreads out about a third, far out the floor.
        assertEquals(1.0, range.fit(range.mean), 1e-9)
        assertEquals(0.39, range.fit(range.mean - 2 * range.spread), 0.01)
        assertEquals(RatingRange.FLOOR, range.fit(2.0), 0.01)
    }

    @Test
    fun `a movie rated outside the range the user usually watches in keeps a share of its score`() {
        val sameTaste = profileFrom(Seed(matrix, 1.0, wanted = false), genres = mapOf(matrix to listOf(sciFi)), people = emptyMap())
        val usuallyHigh = sameTaste.copy(ratingRange = RatingRange(mean = 8.0, spread = 0.5))
        val candidates = listOf(
            candidate(johnWick, 8.0, 700_000, genres = listOf(sciFi)),
            candidate(reloaded, 6.1, 600_000, genres = listOf(sciFi))
        )

        val whenNoRange = RecommendationRanker.rank(sameTaste, candidates, now = NOW)
        val whenRange = RecommendationRanker.rank(usuallyHigh, candidates, now = NOW)

        // Both match the taste alike: the one rated like what the user sees keeps its score, the other its floor share.
        assertEquals("John Wick", whenRange.first().movie.primaryTitle)
        assertEquals(whenNoRange[0].score, whenRange[0].score, 0.01)
        val reloadedBefore = whenNoRange.first { it.movie == reloaded }.score
        val reloadedAfter = whenRange.first { it.movie == reloaded }.score
        assertEquals(usuallyHigh.ratingRange!!.fit(6.1), reloadedAfter / reloadedBefore, 1e-9)
        assertTrue(reloadedAfter / reloadedBefore < 0.35)
    }

    /** A profile the way the service builds it: weights summed per genre, person and decade over the seeds. */
    private fun profileFrom(vararg seeds: Seed, genres: Map<Movie, List<Genre>>, people: Map<Movie, List<Pair<Person, String>>>): TasteProfile {
        val seedsByGenre = seeds.flatMap { seed -> genres[seed.movie].orEmpty().map { it.ref() to seed } }.groupBy({ it.first }, { it.second })
        val seedsByPerson = seeds.flatMap { seed -> people[seed.movie].orEmpty().map { it.first.ref() to seed } }.groupBy({ it.first }, { it.second })
        return TasteProfile(
            seeds = seeds.toList(),
            genreWeights = seedsByGenre.mapValues { (_, seeds) -> seeds.sumOf { it.weight } },
            people = seedsByPerson.mapValues { (_, seeds) -> seeds.sumOf { it.weight } },
            decadeWeights = seeds.groupBy({ decadeOf(it.movie.startYear!!) }, { it.weight }).mapValues { (_, weights) -> weights.sum() },
            seedsByGenre = seedsByGenre,
            seedsByPerson = seedsByPerson
        )
    }
}
