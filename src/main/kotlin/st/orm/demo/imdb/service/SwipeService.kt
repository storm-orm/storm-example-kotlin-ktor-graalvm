package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.GamePlayerPk
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Swipe
import st.orm.demo.imdb.model.SwipePk
import st.orm.demo.imdb.model.SwipeRoundCard
import st.orm.demo.imdb.model.SwipeRoundSwipe
import st.orm.demo.imdb.model.SwipeRoundSwipePk
import st.orm.demo.imdb.model.SwipeWay
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.MovieGenreRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.RecommendationDismissalRepository
import st.orm.demo.imdb.repository.SwipeRepository
import st.orm.demo.imdb.repository.SwipeRoundCardRepository
import st.orm.demo.imdb.repository.SwipeRoundSwipeRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.entityId
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** A poster of a deck: the movie, and its genres, which the card shows under it. */
data class SwipeCard(val movie: Movie, val genres: List<Genre>) {
    /** The line under the poster: "1999 · Action, Sci-Fi". */
    val caption: String
        get() = listOfNotNull(movie.startYear?.toString(), genres.joinToString(", ") { it.name }.ifEmpty { null }).joinToString(" · ")
}

/**
 * What the visitor's swipes say so far: how many went each way, and the
 * genres they swiped right on most, for the line the deck shows after a
 * stack: "You lean towards Thrillers and Crime".
 */
data class SwipeSummary(val wanted: Int, val passed: Int, val seen: Int, val genres: List<Genre>) {
    val total: Int get() = wanted + passed + seen
    /** The genres as the line says them: "Thriller, Crime". */
    val genreNames: String get() = genres.joinToString(", ") { it.name }
}

/** How far the visitor's swipes agree with someone's: of the movies both swiped, how many the same way. */
data class TasteTwin(val user: User, val shared: Int, val agreed: Int) {
    val percent: Int get() = if (shared == 0) 0 else agreed * 100 / shared
}

/** The deck on one's own: the next posters, what the swipes say so far, and whose taste agrees most. */
data class SwipeView(val cards: List<SwipeCard>, val summary: SwipeSummary, val twins: List<TasteTwin>, val company: Company)

/** A movie of a round as its results show it: who swiped right on it. */
data class RoundResult(val movie: Movie, val wantedBy: List<User>)

/**
 * A swipe round with a friend or a club, for a player: the posters they
 * have yet to swipe, how far each player got, and the movies swiped right
 * on: with a friend the matches, both right; with a club the most
 * wanted, the ballot of a movie night.
 */
data class SwipeRoundView(
    val match: GameMatch,
    val cards: List<SwipeCard>,
    val players: List<GamePlayer>,
    val swiped: Map<String, Int>,
    val deckSize: Int,
    val results: List<RoundResult>,
    val over: Boolean,
    val hoursLeft: Long?
) {
    /** How many of the deck a player swiped. */
    fun swipedBy(player: GamePlayer): Int = swiped[player.user.id] ?: 0
}

/**
 * Swiping: posters, one at a time, right for a movie to see, left to pass
 * on it, up for one seen. On one's own the swipes do what the buttons on a
 * movie's page do: right puts it on the watchlist, left takes it out of
 * the recommendations, up marks it watched, some time ago, so the deck is
 * the quickest way to teach For You a taste; a movie swiped once is not
 * dealt again. The deck is the best-known movies of the visitor's taste
 * not on their lists yet.
 *
 * With a friend or a club a round deals the same [ROUND_CARDS] posters to
 * everyone, open two days, whoever swiped: with a friend the movies both
 * want are the matches; with a club the most wanted are the ballot of a
 * movie night. A round's swipes stay with the round.
 *
 * The swipes on one's own say whose taste agrees with whose ([TasteTwin]):
 * of the movies two people both swiped, how many the same way.
 */
class SwipeService(
    private val matchService: MatchService,
    private val swipeRepository: SwipeRepository,
    private val swipeRoundCardRepository: SwipeRoundCardRepository,
    private val swipeRoundSwipeRepository: SwipeRoundSwipeRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val movieGenreRepository: MovieGenreRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val watchlistRepository: WatchlistRepository,
    private val dismissalRepository: RecommendationDismissalRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val userRepository: UserRepository,
    private val watchlistService: WatchlistService,
    private val movieSeenService: MovieSeenService,
    private val recommendationService: RecommendationService
) {

    /* ── On one's own ─────────────────────────────────────────────── */

    /** The deck for the visitor: the next posters, what their swipes say, and their taste twins. */
    suspend fun deckOf(user: User): SwipeView = transaction(readOnly = true) {
        val swipes = swipeRepository.findAll(user)
        val left = swipes.map { it.movie.entityId() }.toSet() +
            movieSeenRepository.findSeenMovies(user).map { it.entityId() } +
            watchlistRepository.findAll(user).map { it.movie.id } +
            dismissalRepository.findAll(user).map { it.movie.id }
        val movies = ratingRepository.findBestKnown(user.movieFilter, MINIMUM_VOTES, POOL)
            .map { it.movie }
            .filter { it.id !in left }
            // The best known first, a little shuffled, so a deck does not deal the same order to everyone.
            .chunked(SHUFFLE_CHUNK).flatMap { it.shuffled() }
            .take(DECK)
        SwipeView(cardsOf(movies), summaryOf(swipes), twinsOf(user), matchService.companyOf(user))
    }

    /**
     * Swipes a movie on one's own: right puts it on the watchlist, left
     * takes it out of the recommendations, up marks it watched some time
     * ago. Runs in the route's transaction, as the list toggles do.
     */
    suspend fun swipe(user: User, movieId: String, way: SwipeWay, now: Instant = Instant.now()) {
        val movie = movieRepository.findById(movieId) ?: return
        val stored = userRepository.touch(user, now)
        val swipe = Swipe(stored, movie, way, now)
        if (swipeRepository.existsById(SwipePk(userId = stored.id, movieId = movie.id))) swipeRepository.update(swipe) else swipeRepository.insert(swipe)
        when (way) {
            SwipeWay.WANT -> if (!watchlistRepository.contains(stored, movie)) watchlistService.toggle(stored, movie.id)
            SwipeWay.PASS -> recommendationService.dismiss(stored, movie.id)
            SwipeWay.SEEN -> if (!movieSeenRepository.contains(stored, movie)) movieSeenService.toggle(stored, movie.id, historic = true)
        }
    }

    private fun cardsOf(movies: List<Movie>): List<SwipeCard> {
        val genres = movieGenreRepository.findByMovies(movies).groupBy({ it.movie.id }, { it.genre })
        return movies.map { SwipeCard(it, genres[it.id].orEmpty().sortedBy { genre -> genre.name }) }
    }

    /** How many swipes went each way, and the genres swiped right on most. */
    private fun summaryOf(swipes: List<Swipe>): SwipeSummary {
        val wanted = swipes.filter { it.way == SwipeWay.WANT }
        val movies = movieRepository.findAllById(wanted.map { it.movie.entityId() })
        val genres = movieGenreRepository.findByMovies(movies).groupingBy { it.genre }.eachCount()
            .entries.sortedByDescending { it.value }.take(SUMMARY_GENRES).map { it.key }
        return SwipeSummary(
            wanted = wanted.size,
            passed = swipes.count { it.way == SwipeWay.PASS },
            seen = swipes.count { it.way == SwipeWay.SEEN },
            genres = genres
        )
    }

    /**
     * Whose swipes agree most with the visitor's, among their friends and
     * the members of their clubs: of the movies both swiped, how many the
     * same way; only someone with [TWIN_MINIMUM] movies in common counts.
     */
    private fun twinsOf(user: User): List<TasteTwin> {
        if (user.name == null) return emptyList()
        val friends = friendshipRepository.findFriends(user).map { it.friend }
        val members = clubMemberRepository.findMembers(clubMemberRepository.findClubs(user).map { it.club }).map { it.user }
        val others = (friends + members).filter { it.id != user.id }.distinctBy { it.id }
        if (others.isEmpty()) return emptyList()
        val mine = swipeRepository.findAll(user).associate { it.movie.entityId() to it.way }
        return swipeRepository.findSharedWith(user, others)
            .groupBy { it.user.entityId() }
            .mapNotNull { (id, theirs) ->
                val agreed = theirs.count { mine[it.movie.entityId()] == it.way }
                others.firstOrNull { it.id == id }?.let { TasteTwin(it, theirs.size, agreed) }
            }
            .filter { it.shared >= TWIN_MINIMUM }
            .sortedWith(compareByDescending<TasteTwin> { it.percent }.thenByDescending { it.shared })
    }

    /* ── Rounds with a friend or a club ───────────────────────────── */

    /**
     * Starts a round with a friend or a club: a deck of the best-known
     * movies of the host's taste that none of the players has seen; null
     * when the friend or the club is not the host's.
     */
    suspend fun startRound(host: User, opponents: Opponents, now: Instant = Instant.now()): GameMatch? = transaction {
        if (opponents.mode == GameMode.SOLO) return@transaction null
        val stored = userRepository.touch(host, now)
        val (club, players) = matchService.playersOf(stored, opponents) ?: return@transaction null
        val seen = movieSeenRepository.findSeenBy(players).map { it.movie.entityId() }.toSet()
        val movies = ratingRepository.findBestKnown(stored.movieFilter, MINIMUM_VOTES, POOL)
            .map { it.movie }
            .filter { it.id !in seen }
            .shuffled(Random(now.toEpochMilli()))
            .take(ROUND_CARDS)
        if (movies.isEmpty()) return@transaction null
        val (match, _) = matchService.start(
            GameMatch(game = Game.SWIPE, mode = opponents.mode, club = club, host = stored, createdAt = now,
                closesAt = now.plus(MatchService.ROUND), endedAt = null),
            // A club's members join the round as they swipe it; a friend is in it from the start.
            if (opponents.mode == GameMode.CLUB) listOf(stored) else players,
            now
        )
        swipeRoundCardRepository.insert(movies.mapIndexed { position, movie -> SwipeRoundCard(match, position, movie) })
        match
    }

    /** The round for the visitor, or null when they may not look at it. */
    suspend fun roundOf(user: User, matchId: Long, now: Instant = Instant.now()): SwipeRoundView? = transaction(readOnly = true) {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.SWIPE } ?: return@transaction null
        val cards = swipeRoundCardRepository.findCards(match).map { it.movie }
        val swipes = swipeRoundSwipeRepository.findSwipes(match)
        val players = gamePlayerRepository.findPlayers(match)
        val over = match.isOver(now)
        val mine = swipes.filter { it.user.entityId() == user.id }.map { it.movie.entityId() }.toSet()
        val wanted = swipes.filter { it.way == SwipeWay.WANT }.groupBy({ it.movie.entityId() }, { it.user.entityId() })
        val users = players.associate { it.user.id to it.user }
        val results = cards.mapNotNull { movie ->
            val by = wanted[movie.id].orEmpty().mapNotNull(users::get)
            RoundResult(movie, by).takeIf { by.isNotEmpty() }
        }
        SwipeRoundView(
            match = match,
            cards = if (over) emptyList() else cardsOf(cards.filter { it.id !in mine }),
            players = players,
            swiped = swipes.groupingBy { it.user.entityId() }.eachCount(),
            deckSize = cards.size,
            results = when (match.mode) {
                // With a friend: the matches, the movies both want.
                GameMode.FRIEND -> results.filter { it.wantedBy.size == players.size }
                // With a club: the most wanted, the ballot of a movie night.
                else -> results.sortedByDescending { it.wantedBy.size }.take(BALLOT)
            },
            over = over,
            hoursLeft = match.closesAt?.let { Duration.between(now, it).toHours().coerceAtLeast(0) }
        )
    }

    /** Swipes a movie of the round; a club's member joins the round with their first swipe. Nothing once it is closed. */
    suspend fun swipeInRound(user: User, matchId: Long, movieId: String, way: SwipeWay, now: Instant = Instant.now()) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.SWIPE && !it.isOver(now) } ?: return@transaction
        val movie = swipeRoundCardRepository.findCards(match).map { it.movie }.firstOrNull { it.id == movieId } ?: return@transaction
        val stored = userRepository.touch(user, now)
        val player = gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = stored.id))
            ?: (if (match.mode == GameMode.CLUB) GamePlayer(match, stored, gamePlayerRepository.findPlayers(match).size, now).also(gamePlayerRepository::insert) else null)
            ?: return@transaction
        val swipe = SwipeRoundSwipe(match, stored, movie, way, now)
        if (swipeRoundSwipeRepository.existsById(SwipeRoundSwipePk(matchId = match.id, userId = stored.id, movieId = movie.id))) swipeRoundSwipeRepository.update(swipe)
        else swipeRoundSwipeRepository.insert(swipe)
        // Every poster swiped: the player's part is done.
        val done = swipeRoundSwipeRepository.findSwipes(match).count { it.user.entityId() == stored.id } >= swipeRoundCardRepository.findCards(match).size
        if (done != player.finished) gamePlayerRepository.update(player.copy(finished = done, updatedAt = now))
    }

    companion object {
        /** The posters of a deck on one's own: a stack, then what it says. */
        const val DECK = 20
        /** The posters of a round, the same for everyone in it. */
        const val ROUND_CARDS = 30
        /** A club's round puts its most wanted on the ballot: as many as a movie night's candidates. */
        const val BALLOT = 6
        private const val POOL = 600
        private const val MINIMUM_VOTES = 25_000
        private const val SHUFFLE_CHUNK = 40
        private const val SUMMARY_GENRES = 3
        /** A taste twin has swiped at least this many of the same movies. */
        private const val TWIN_MINIMUM = 5
    }
}
