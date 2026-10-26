package st.orm.demo.imdb.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import st.orm.Ref
import st.orm.Window
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Reason
import st.orm.demo.imdb.model.ReasonKind
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.model.RecommendationDismissal
import st.orm.demo.imdb.model.RecommendationEvent
import st.orm.demo.imdb.model.RecommendationEventKind
import st.orm.demo.imdb.model.RecommendationPk
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.DIRECTOR
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.demo.imdb.repository.MovieCountryRepository
import st.orm.demo.imdb.repository.MovieLanguageRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.MovieViewCount
import st.orm.demo.imdb.repository.MovieViewRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
import st.orm.demo.imdb.repository.RecommendationEventRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.repository.RecommendationRepository.Companion.bestFirst
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.pow

/** A recommended movie as the pages show it: the movie and why. */
data class RecommendedMovie(val movie: Movie, val reasons: List<Reason>)

/**
 * A shelf of picks that one movie of the visitor's own argued for, under
 * its reason as the title: "Because you saw X", or "Because X is on your
 * watchlist".
 */
data class Shelf(val seed: Movie, val title: String, val movies: List<RecommendedMovie>)

/** The recommendation sections of the home page. */
data class HomeRecommendations(
    /** Whether the picks come from the visitor's own lists rather than the catalogue's. */
    val personal: Boolean,
    /** The hero at the top of the page: one of the best picks, at random, so each visit opens on another. */
    val featured: RecommendedMovie?,
    val watchNext: List<RecommendedMovie>,
    val shelves: List<Shelf>
)

/**
 * The For You page: the picks that joined the catalogue lately, the first
 * window of the visitor's picks and the cursor to scroll on, or the
 * catalogue's picks.
 */
/** A page's view, and the picks on it that count as shown once the page's read has closed. */
private data class Shown<T>(val view: T, val picks: List<Recommendation>)

data class ForYouView(
    val personal: Boolean,
    val newArrivals: List<RecommendedMovie>,
    /** How far back the new arrivals reach: since the previous visit, within bounds. */
    val newSince: Duration,
    val movies: List<RecommendedMovie>,
    val nextCursor: String?
)

/**
 * What to watch next. A visitor's recommendations are computed from their
 * lists in the background and stored ([refreshLater], [refresh]); the pages
 * read the stored rows. A visitor whose lists are empty gets the
 * catalogue's own picks, computed by the same ranking with an empty taste
 * profile and shared by everyone for an hour.
 *
 * The computation is three steps: the taste profile from the seeds, the
 * candidates from a few bounded queries, and the ranking in Kotlin
 * ([RecommendationRanker]). SQL narrows a catalogue of a hundred thousand
 * movies to a couple of thousand rows by index; the scoring formula is one
 * readable function with a unit test.
 *
 * Two things keep the list from going stale. Each pick counts how often
 * the home page or the For You page showed it, and the count decays its
 * score at the next computation, so the picks passed over make way; a pick
 * the visitor opened starts counting afresh. And a visitor can dismiss a
 * pick, which keeps it out for good and weighs a little against its kind.
 */
class RecommendationService(
    private val userRepository: UserRepository,
    private val movieRepository: MovieRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val watchlistRepository: WatchlistRepository,
    private val dismissalRepository: RecommendationDismissalRepository,
    private val movieAdditionRepository: MovieAdditionRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val principalRepository: PrincipalRepository,
    private val ratingRepository: RatingRepository,
    private val movieViewRepository: MovieViewRepository,
    private val recommendationRepository: RecommendationRepository,
    private val recommendationEventRepository: RecommendationEventRepository,
    private val checkInRepository: CheckInRepository,
    private val movieLanguageRepository: MovieLanguageRepository,
    private val movieCountryRepository: MovieCountryRepository
) {
    private val logger = LoggerFactory.getLogger(RecommendationService::class.java)

    private val refreshRequests = Channel<String>(Channel.UNLIMITED)
    private val pendingRefreshes = ConcurrentHashMap.newKeySet<String>()

    /** The catalogue's picks for visitors without lists, and when they were computed. */
    private class ColdStart(val computedAt: Instant, val picks: List<RecommendedMovie>) {
        fun isFresh(): Boolean = computedAt.isAfter(Instant.now().minus(COLD_START_MAX_AGE))
    }

    private val coldStartLock = Mutex()
    @Volatile private var coldStart: ColdStart? = null

    /**
     * The most viewed movies of the week, and when they were counted. The
     * count aggregates every view of the week, which is the costliest query
     * of a computation by far and the same for every visitor, so it is made
     * once an hour and shared.
     */
    private class Trending(val countedAt: Instant, val viewCounts: List<MovieViewCount>) {
        fun isFresh(): Boolean = countedAt.isAfter(Instant.now().minus(TRENDING_MAX_AGE))
    }

    @Volatile private var trending: Trending? = null

    /* ── Reading ──────────────────────────────────────────────────── */

    /**
     * The recommendation sections of the home page for the visitor. The
     * featured movie is drawn at random from the picks the For You page
     * opens with, and the row under it leaves it out. The picks shown count
     * as shown ([markShown]).
     */
    suspend fun forHome(user: User): HomeRecommendations {
        val read = transaction(readOnly = true) {
            val recommendations = recommendationRepository.findAll(user)
            if (recommendations.isEmpty()) {
                refreshIfSeeded(user)
                val picks = coldStartPicks(user.movieFilter)
                val featured = picks.take(PAGE_SIZE).randomOrNull()
                Shown(
                    HomeRecommendations(
                        personal = false,
                        featured = featured,
                        watchNext = picks.filter { it != featured }.take(WATCH_NEXT_LIMIT),
                        shelves = emptyList()
                    ),
                    picks = emptyList()
                )
            } else {
                val featured = recommendations.take(PAGE_SIZE).random()
                val watchNext = (recommendations - featured).take(WATCH_NEXT_LIMIT)
                val shelves = shelves(recommendations)
                Shown(
                    HomeRecommendations(
                        personal = true,
                        featured = featured.toRecommendedMovie(),
                        watchNext = watchNext.map { it.toRecommendedMovie() },
                        shelves = shelves.map { Shelf(it.seed, it.title, it.picks.map { pick -> pick.toRecommendedMovie() }) }
                    ),
                    picks = (listOf(featured) + watchNext + shelves.flatMap { it.picks }).distinctBy { it.movie.id }
                )
            }
        }
        markShown(user, read.picks, Instant.now())
        return read.view
    }

    /**
     * Counts the picks as shown and the visitor as seen, in one batched
     * update and one upsert, in a transaction of its own once the page's
     * read has closed. Never inside the read: a write of its own there would
     * hold a second connection while the first is held, and a burst of pages
     * each holding one and waiting for a second takes every connection of
     * the pool, so nothing gets one until they time out. A recomputation
     * that replaces the rows between the page's read and this write is
     * harmless: the update locks the rows that still exist and counts only
     * those.
     */
    private suspend fun markShown(user: User, shown: List<Recommendation>, now: Instant) {
        if (shown.isEmpty()) return
        transaction {
            recommendationRepository.recordShown(user, shown, now)
            userRepository.touch(user, now)
        }
    }

    /**
     * The For You page for the visitor: the first window of their picks, or
     * the catalogue's. The first window counts as shown; the windows the
     * scroll fetches after it ([scroll]) do not, so a long scroll is not a
     * long run of writes.
     */
    suspend fun forYou(user: User): ForYouView {
        val read = transaction(readOnly = true) {
            val window = recommendationRepository.scroll(user, bestFirst(PAGE_SIZE))
            if (window.content().isEmpty()) {
                refreshIfSeeded(user)
                val picks = coldStartPicks(user.movieFilter)
                Shown(
                    ForYouView(
                        personal = false,
                        newArrivals = picks.filter { pick -> pick.reasons.any { it.kind == ReasonKind.NEW } }.take(NEW_ARRIVALS_LIMIT),
                        newSince = NEW_ARRIVALS_FLOOR,
                        movies = picks,
                        nextCursor = null
                    ),
                    picks = emptyList()
                )
            } else {
                val now = Instant.now()
                val newSince = newArrivalsWindow(user, now)
                val newArrivals = newArrivals(user, now.minus(newSince), now)
                Shown(
                    ForYouView(
                        personal = true,
                        newArrivals = newArrivals.map { it.toRecommendedMovie() },
                        newSince = newSince,
                        movies = window.content().map { it.toRecommendedMovie() },
                        nextCursor = window.nextCursor()
                    ),
                    picks = (newArrivals + window.content()).distinctBy { it.movie.id }
                )
            }
        }
        markShown(user, read.picks, Instant.now())
        return read.view
    }

    /**
     * How far back "new since your last visit" reaches: the time since the
     * visitor was last seen, never less than a week, so a daily visitor
     * still sees the week's arrivals, and never more than a quarter, so a
     * visitor back after a year gets a quarter's worth, not the catalogue.
     */
    private fun newArrivalsWindow(user: User, now: Instant): Duration {
        val sinceLastVisit = Duration.between(user.lastSeenAt, now)
        return if (sinceLastVisit < NEW_ARRIVALS_FLOOR) NEW_ARRIVALS_FLOOR else if (sinceLastVisit > NEW_ARRIVALS_CEILING) NEW_ARRIVALS_CEILING else sinceLastVisit
    }

    /**
     * The visitor's picks among the movies that joined the catalogue since
     * a moment, best first: the picks are at most a hundred rows and the
     * arrivals a bounded query, so the match is made in memory. Only picks
     * that fit the visitor appear, and nothing appears when none does.
     */
    private fun newArrivals(user: User, since: Instant, now: Instant): List<Recommendation> {
        val arrived = movieAdditionRepository.findAddedSince(since, recentYear(now), NEW_ARRIVALS_SCAN).map { it.movie.id }.toSet()
        if (arrived.isEmpty()) return emptyList()
        return recommendationRepository.findAll(user).filter { it.movie.id in arrived }.take(NEW_ARRIVALS_LIMIT)
    }

    /**
     * All the visitor's picks, best first, or the catalogue's for a visitor
     * without picks: a hundred at most, for For You kept to the visitor's
     * streaming services, which needs all of them at once.
     */
    suspend fun allPicks(user: User): List<RecommendedMovie> = transaction(readOnly = true) {
        recommendationRepository.findAll(user).map { it.toRecommendedMovie() }.ifEmpty { coldStartPicks(user.movieFilter) }
    }

    /** The next window of the visitor's picks, for the infinite scroll. */
    suspend fun scroll(user: User, cursor: String): Window<Recommendation> = transaction(readOnly = true) {
        recommendationRepository.scroll(user, bestFirst(PAGE_SIZE).from(cursor))
    }

    /** A shelf with its rows, before they turn into what the page shows. */
    private class ShelfRows(val seed: Movie, val title: String, val picks: List<Recommendation>)

    /**
     * The "Because you saw" shelves: the seeds that argued for the most
     * picks, each with its picks in order. A visitor has at most a hundred
     * recommendations, so this groups what the home page read already.
     */
    private fun shelves(recommendations: List<Recommendation>): List<ShelfRows> =
        recommendations
            .filter { it.seedMovie != null }
            .groupBy { it.seedMovie!!.id }
            .values
            .sortedByDescending { it.size }
            .take(SHELF_COUNT)
            .map { picks ->
                ShelfRows(
                    seed = picks.first().seedMovie!!,
                    title = picks.first().reasons.first { it.kind == ReasonKind.SEED }.text,
                    picks = picks.take(SHELF_SIZE)
                )
            }

    private fun Recommendation.toRecommendedMovie() = RecommendedMovie(movie, reasons)

    /** A visitor with seeds but no rows yet, after a deploy of this feature, gets them computed. */
    private fun refreshIfSeeded(user: User) {
        if (movieSeenRepository.findAll(user).isNotEmpty() || watchlistRepository.findAll(user).isNotEmpty()) refreshLater(user)
    }

    /* ── Events ───────────────────────────────────────────────────── */

    /**
     * Records that something came of a recommendation, if the movie is one
     * of the user's. Called inside the transaction of the write it belongs
     * to: the view of a movie opened from a recommendation surface, or a
     * toggle that saved or marked seen a recommended movie. An opened pick
     * starts its impression count afresh: it worked, so it should not fade.
     */
    fun record(user: User, movie: Movie, kind: RecommendationEventKind) {
        if (recommendationRepository.contains(user, movie)) {
            recommendationEventRepository.insert(RecommendationEvent(user = user.ref(), movie = movie.ref(), kind = kind, at = Instant.now()))
            if (kind == RecommendationEventKind.OPENED) recommendationRepository.resetShown(user, movie)
        }
    }

    /**
     * "Not interested": the movie leaves the user's recommendations now and
     * for good, and the user's picks are recomputed with it weighing a
     * little against its kind. Runs in the route's transaction.
     */
    suspend fun dismiss(user: User, movieId: String) {
        val movie = movieRepository.getById(movieId)
        val now = Instant.now()
        val stored = userRepository.touch(user, now)
        if (!dismissalRepository.contains(stored, movie)) {
            record(stored, movie, RecommendationEventKind.DISMISSED)
            dismissalRepository.insert(RecommendationDismissal(stored, movie, now))
            recommendationRepository.findById(RecommendationPk(userId = stored.id, movieId = movie.id))?.let { recommendationRepository.remove(it) }
        }
        refreshLater(stored)
    }

    /* ── Computing ────────────────────────────────────────────────── */

    /**
     * Asks for the user's recommendations to be recomputed. Called by the
     * writes that change the lists; the work happens on the worker
     * ([processRefreshRequests]), so the write returns at once. A user asked
     * for twice before the worker gets there is computed once.
     */
    fun refreshLater(user: User) {
        if (pendingRefreshes.add(user.id)) refreshRequests.trySend(user.id)
    }

    /**
     * The worker, launched once in the application's scope, which cancels
     * it at shutdown. It waits a moment before each run, so the transaction
     * of the toggle that asked has committed, and a burst of toggles on one
     * page is one computation. A failure is logged; the next change computes again.
     */
    suspend fun processRefreshRequests() {
        for (userId in refreshRequests) {
            delay(REFRESH_DELAY.toMillis())
            pendingRefreshes.remove(userId)
            try {
                refresh(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Could not compute the recommendations of account $userId.", e)
            }
        }
    }

    /**
     * Computes and stores the user's recommendations, in one transaction:
     * the pages see the old list until the new one is in. The impression
     * counts of the picks that stay carry over, so a pick does not start
     * afresh at each computation. An account that is gone, merged into
     * another at a sign-in, has nothing to compute.
     */
    suspend fun refresh(userId: String) = transaction {
        val user = userRepository.findById(userId) ?: return@transaction
        val started = System.nanoTime()
        val now = Instant.now()
        val previous = recommendationRepository.findAll(user).associateBy { it.movie.ref() }
        val profile = profileOf(user, now)
        val candidates = candidatesFor(profile, user.movieFilter).map { candidate ->
            candidate.copy(shownCount = previous[candidate.movie.ref()]?.shownCount ?: 0)
        }
        val picks = RecommendationRanker.rank(profile, candidates, now)
        recommendationRepository.replace(user, picks.map {
            val before = previous[it.movie.ref()]
            Recommendation(
                user, it.movie, BigDecimal.valueOf(it.score).setScale(SCORE_SCALE, RoundingMode.HALF_UP), it.seed?.movie, it.reasons, now,
                shownCount = before?.shownCount ?: 0,
                lastShownAt = before?.lastShownAt
            )
        })
        logger.info(
            "Computed {} recommendations for an account from {} seeds and {} candidates in {} ms.",
            picks.size, profile.seeds.size, candidates.size, (System.nanoTime() - started) / 1_000_000
        )
    }

    /**
     * What the user's lists say they like. The seeds are the seen movies,
     * weighed by their vote, the watchlist, and the dismissed picks, which
     * weigh against. What happened lately counts most: a seed's weight
     * halves every [RECENCY_HALF_LIFE], down to a floor, so a taste that
     * moved on is followed and an old favourite still counts for something;
     * a movie seen without a check-in weighs as the floor, as its day is not known.
     * Two queries bring the seeds' genres and the credits their taste is
     * read from, the top-billed cast and the director, and the weights are
     * summed per genre, per person and per decade. A director weighs more
     * than an actor: the director's hand is what the next movie shares.
     * One query more brings the ratings of the seen movies, which tell
     * the range the user usually watches in; a thumbs down does not count
     * towards it, since that movie was not to their taste.
     */
    private fun profileOf(user: User, now: Instant): TasteProfile {
        val seen = movieSeenRepository.findAll(user)
        val checkedIn = checkInRepository.findLatestPerMovie(user).associate { it.movie.entityId() to it.watchedAt }
        val seeds = seen.map { Seed(it.movie, seenWeight(it.liked) * seenRecency(checkedIn[it.movie.id], now), wanted = false) } +
            watchlistRepository.findAll(user).map { Seed(it.movie, WATCHLIST_WEIGHT * recencyFactor(it.addedAt, now), wanted = true) } +
            dismissalRepository.findAll(user).map { Seed(it.movie, DISMISSED_WEIGHT * recencyFactor(it.dismissedAt, now), wanted = false) }
        if (seeds.isEmpty()) return TasteProfile.EMPTY
        val seenRatings = ratingRepository.findByMovies(seen.filter { it.liked != false }.map { it.movie.ref() })
        val seedsByMovie = seeds.associateBy { it.movie.ref() }
        val seedsByGenre = movieGenreRepository.findByMovies(seeds.map { it.movie })
            .groupBy({ it.genre.ref() }, { seedsByMovie.getValue(it.movie.ref()) })
        val credits = principalRepository.findTasteCredits(seeds.map { it.movie.ref() }, TOP_BILLING)
        val seedsByPerson = credits.groupBy({ it.person.ref() }, { seedsByMovie.getValue(it.movie) })
        val personWeights = credits
            .groupBy { it.person.ref() }
            .mapValues { (_, credits) -> credits.sumOf { seedsByMovie.getValue(it.movie).weight * if (it.category == DIRECTOR) DIRECTOR_FACTOR else 1.0 } }
        val decadeWeights = seeds
            .mapNotNull { seed -> seed.movie.startYear?.let { decadeOf(it) to seed.weight } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, weights) -> weights.sum() }
        // Two queries more: the seeds' languages and countries, weighed like their decades.
        val languageWeights = movieLanguageRepository.findByMovies(seedsByMovie.keys)
            .groupBy({ it.id.language }, { seedsByMovie.getValue(it.movie).weight })
            .mapValues { (_, weights) -> weights.sum() }
        val countryWeights = movieCountryRepository.findByMovies(seedsByMovie.keys)
            .groupBy({ it.id.country }, { seedsByMovie.getValue(it.movie).weight })
            .mapValues { (_, weights) -> weights.sum() }
        return TasteProfile(
            seeds = seeds,
            genreWeights = seedsByGenre.mapValues { (_, seeds) -> seeds.sumOf { it.weight } },
            people = personWeights,
            decadeWeights = decadeWeights,
            seedsByGenre = seedsByGenre,
            seedsByPerson = seedsByPerson,
            ratingRange = RatingRange.of(seenRatings),
            languageWeights = languageWeights,
            countryWeights = countryWeights,
            homeCountry = user.country
        )
    }

    /** How much a seen movie's weight keeps: by the day of its latest check-in, or as the oldest taste does without one. */
    private fun seenRecency(checkedInAt: Instant?, now: Instant) =
        if (checkedInAt == null) RECENCY_FLOOR else recencyFactor(checkedInAt, now)

    private fun seenWeight(liked: Boolean?) = when (liked) {
        true -> LIKED_WEIGHT
        false -> DISLIKED_WEIGHT
        null -> SEEN_WEIGHT
    }

    /**
     * The candidates: the filmographies of the favourite people, the
     * best-rated movies of the favourite genres, the best-rated movies of
     * the catalogue, the most viewed of the week, and the recent releases
     * that joined the catalogue lately, each a bounded query by index, minus
     * the seeds, dismissed picks among them. One query more brings the
     * candidates' genres, and two their languages and countries. With a [filter], the best-rated queries keep to the
     * movies it lets through, so they still bring their full share, and one
     * query more keeps the rest of the candidates to them.
     */
    private fun candidatesFor(profile: TasteProfile, filter: MovieFilter?): List<Candidate> {
        val now = Instant.now()
        val favouritePeople = profile.people.filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(FAVOURITE_PEOPLE).map { it.key }
        val favouriteGenres = profile.genreWeights.filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(FAVOURITE_GENRES).map { it.key }

        val credits = if (favouritePeople.isEmpty()) emptyList() else principalRepository.findFilmographies(favouritePeople)
        val viewCounts = trendingViewCounts(now)
        val additions = movieAdditionRepository.findAddedSince(now.minus(RecommendationRanker.NEW_WINDOW), recentYear(now), NEW_LIMIT)

        val ratings = LinkedHashMap<Ref<Movie>, Rating>()
        fun consider(rating: Rating) = ratings.putIfAbsent(rating.movie.ref(), rating)
        credits.forEach { consider(it.rating) }
        favouriteGenres.forEach { genre -> ratingRepository.findTopRatedInGenre(genre, TOP_RATED_MINIMUM_VOTES, filter, TOP_RATED_PER_GENRE).forEach(::consider) }
        ratingRepository.findTopRated(TOP_RATED_MINIMUM_VOTES, filter, TOP_RATED_LIMIT).forEach(::consider)
        val lookedUp = viewCounts.map { it.movie } + additions.map { it.movie.ref() }
        if (lookedUp.isNotEmpty()) ratingRepository.findByMovies(lookedUp.distinct()).forEach(::consider)
        profile.seeds.forEach { ratings.remove(it.movie.ref()) }
        if (filter != null) ratings.keys.retainAll(movieRepository.findMatching(ratings.keys, filter))

        val genresByMovie = movieGenreRepository.findByMovies(ratings.values.map { it.movie }).groupBy({ it.movie.ref() }, { it.genre })
        val languagesByMovie = movieLanguageRepository.findByMovies(ratings.keys).groupBy({ it.movie }, { it.id.language })
        val countriesByMovie = movieCountryRepository.findByMovies(ratings.keys).groupBy({ it.movie }, { it.id.country })
        val creditsByMovie = credits.groupBy { it.rating.movie.ref() }
        val viewsByMovie = viewCounts.associate { it.movie to it.viewCount }
        val addedAtByMovie = additions.associate { it.movie.ref() to it.addedAt }
        return ratings.map { (movie, rating) ->
            Candidate(
                rating, genresByMovie[movie].orEmpty(), creditsByMovie[movie].orEmpty(), viewsByMovie[movie] ?: 0L,
                addedAt = addedAtByMovie[movie],
                languages = languagesByMovie[movie].orEmpty().toSet(),
                countries = countriesByMovie[movie].orEmpty().toSet()
            )
        }
    }

    /** The week's most viewed movies, counted at most once an hour; a stale count serves until the new one is in. */
    private fun trendingViewCounts(now: Instant): List<MovieViewCount> {
        trending?.takeIf { it.isFresh() }?.let { return it.viewCounts }
        val counted = Trending(now, movieViewRepository.findMostViewedSince(now.minus(TRENDING_WINDOW), TRENDING_LIMIT))
        trending = counted
        return counted.viewCounts
    }

    /**
     * The catalogue's picks for a visitor without lists: the ranking with an
     * empty profile, so quality, recency and what is trending decide.
     * Shared by every such visitor and recomputed after an hour. A visitor
     * who filters the movies ([MovieFilter]) gets picks of their own, among
     * the movies the filter lets through, computed on the spot: few visitors
     * set a filter before they have lists.
     */
    private suspend fun coldStartPicks(filter: MovieFilter?): List<RecommendedMovie> {
        if (filter != null) return computeColdStart(filter).picks
        val current = coldStart
        if (current != null && current.isFresh()) return current.picks
        return coldStartLock.withLock {
            val latest = coldStart
            if (latest != null && latest.isFresh()) {
                latest.picks
            } else {
                computeColdStart(null).also { coldStart = it }.picks
            }
        }
    }

    private suspend fun computeColdStart(filter: MovieFilter?): ColdStart = transaction(readOnly = true) {
        val now = Instant.now()
        val picks = RecommendationRanker.rank(TasteProfile.EMPTY, candidatesFor(TasteProfile.EMPTY, filter), now, COLD_START_LIMIT)
        ColdStart(now, picks.map { RecommendedMovie(it.movie, it.reasons) })
    }

    companion object {
        const val PAGE_SIZE = 24
        private const val WATCH_NEXT_LIMIT = 12
        private const val SHELF_COUNT = 3
        private const val SHELF_SIZE = 8
        private const val COLD_START_LIMIT = 48

        private const val LIKED_WEIGHT = 1.0
        private const val SEEN_WEIGHT = 0.6
        private const val WATCHLIST_WEIGHT = 0.3
        private const val DISLIKED_WEIGHT = -0.8
        private const val DISMISSED_WEIGHT = -0.3

        /** A seed's weight halves every half-life since it was seen, saved or dismissed, never below the floor. */
        private val RECENCY_HALF_LIFE: Duration = Duration.ofDays(90)
        private const val RECENCY_FLOOR = 0.25

        /**
         * How much of its weight a seed keeps after a while: one at the moment
         * itself, a half after a half-life, a quarter after two, the floor
         * from then on.
         */
        fun recencyFactor(at: Instant, now: Instant): Double {
            val halfLives = max(0.0, Duration.between(at, now).toMillis().toDouble() / RECENCY_HALF_LIFE.toMillis())
            return max(RECENCY_FLOOR, 0.5.pow(halfLives))
        }

        /** The cast credits a seed's taste is read from, by billing order; the director is read regardless. */
        private const val TOP_BILLING = 4

        /** How much more a director weighs than an actor on the same seed. */
        private const val DIRECTOR_FACTOR = 1.5
        private const val FAVOURITE_PEOPLE = 20
        private const val FAVOURITE_GENRES = 3
        private const val TOP_RATED_MINIMUM_VOTES = 25_000
        private const val TOP_RATED_PER_GENRE = 100
        private const val TOP_RATED_LIMIT = 100
        private const val TRENDING_LIMIT = 50
        private val TRENDING_WINDOW: Duration = Duration.ofDays(7)
        private val TRENDING_MAX_AGE: Duration = Duration.ofHours(1)

        /** The movies that joined the catalogue within the ranker's new window considered as candidates. */
        private const val NEW_LIMIT = 500

        /** The "new since your last visit" strip: its length, how far back it reaches at least and at most, and the arrivals scanned. */
        private const val NEW_ARRIVALS_LIMIT = 8
        private val NEW_ARRIVALS_FLOOR: Duration = Duration.ofDays(7)
        private val NEW_ARRIVALS_CEILING: Duration = Duration.ofDays(90)
        private const val NEW_ARRIVALS_SCAN = 2_000

        /** The decimals a stored score keeps; the column is NUMERIC(8, 4). */
        private const val SCORE_SCALE = 4

        private val REFRESH_DELAY: Duration = Duration.ofSeconds(2)
        private val COLD_START_MAX_AGE: Duration = Duration.ofHours(1)
    }
}
