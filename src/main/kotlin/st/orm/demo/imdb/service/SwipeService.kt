package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Club
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
import java.util.Locale
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

/** A movie wanted, and how long it took to say so: the quickest yes first. */
data class TimedMovie(val movie: Movie, val decidedMs: Int) {
    /** The time as the page says it: "0.9 s". */
    val seconds: String get() = "%.1f s".format(Locale.ENGLISH, decidedMs / 1000.0)
}

/** The deck on one's own: the next posters, what the swipes say so far, the quickest yeses, and whose taste agrees most. */
data class SwipeView(val cards: List<SwipeCard>, val summary: SwipeSummary, val quickest: List<TimedMovie>, val twins: List<TasteTwin>, val company: Company)

/**
 * A movie of a round as its results show it: who swiped right on it, and
 * how quickly they decided on average, which breaks a tie: the movie
 * wanted without a second thought beats the one pondered.
 */
data class RoundResult(val movie: Movie, val wantedBy: List<User>, val decidedMs: Int?) {
    val seconds: String? get() = decidedMs?.let { "%.1f s".format(Locale.ENGLISH, it / 1000.0) }
}

/**
 * A player's part of a round: how far they got, and once they finished,
 * the movies they wanted, the quickest yes first. The wants show once the
 * viewer finished too, or the round closed, so nobody swipes with the
 * others' answers in view.
 */
data class PlayerSwipes(val player: GamePlayer, val swiped: Int, val finished: Boolean, val shown: Boolean, val wanted: List<TimedMovie>)

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
    /** Each player's part, the viewer first, the finished before the rest. */
    val byPlayer: List<PlayerSwipes>,
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
    private val recommendationService: RecommendationService,
    private val poolService: PoolService
) {

    /* ── On one's own ─────────────────────────────────────────────── */

    /** The deck for the visitor: the next posters, what their swipes say, and their taste twins. */
    suspend fun deckOf(user: User): SwipeView = transaction(readOnly = true) {
        val swipes = swipeRepository.findAll(user)
        val left = swipes.map { it.movie.entityId() }.toSet() +
            movieSeenRepository.findSeenMovies(user).map { it.entityId() } +
            watchlistRepository.findAll(user).map { it.movie.id } +
            dismissalRepository.findAll(user).map { it.movie.id }
        // The movies the site would recommend: the visitor's picks, the best known of their taste when they have none yet.
        val movies = poolService.poolOf(user, listOf(user), PoolKind.RECOMMENDED, MINIMUM_VOTES, POOL).movies
            .filter { it.id !in left }
            // The best known first, a little shuffled, so a deck does not deal the same order to everyone.
            .chunked(SHUFFLE_CHUNK).flatMap { it.shuffled() }
            .take(DECK)
        SwipeView(cardsOf(movies), summaryOf(swipes), quickestOf(swipes), twinsOf(user), matchService.companyOf(user))
    }

    /** The visitor's quickest yeses, the movies wanted in the least time, with the movie read for each. */
    private fun quickestOf(swipes: List<Swipe>): List<TimedMovie> {
        val quick = swipes.filter { it.way == SwipeWay.WANT && it.decidedMs != null }.sortedBy { it.decidedMs }.take(QUICKEST)
        val movies = movieRepository.findAllById(quick.map { it.movie.entityId() }).associateBy { it.id }
        return quick.mapNotNull { swipe -> movies[swipe.movie.entityId()]?.let { TimedMovie(it, swipe.decidedMs!!) } }
    }

    /**
     * Swipes a movie on one's own: right puts it on the watchlist, left
     * takes it out of the recommendations, up marks it watched some time
     * ago. Runs in the route's transaction, as the list toggles do.
     */
    suspend fun swipe(user: User, movieId: String, way: SwipeWay, now: Instant = Instant.now(), decidedMs: Int? = null) {
        val movie = movieRepository.findById(movieId) ?: return
        val stored = userRepository.touch(user, now)
        val swipe = Swipe(stored, movie, way, now, decidedMs)
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
        // The movies the site would recommend the players, none of which any of them watched.
        val pool = poolService.poolOf(stored, players, GameRules.of(Game.SWIPE).pool, MINIMUM_VOTES, POOL)
        val movies = pool.movies.shuffled(Random(now.toEpochMilli())).take(ROUND_CARDS)
        if (movies.isEmpty()) return@transaction null
        val (match, _) = matchService.start(
            GameMatch(game = Game.SWIPE, mode = opponents.mode, club = club, host = stored, createdAt = now,
                closesAt = now.plus(MatchService.ROUND), endedAt = null),
            // A club's members join the round as they swipe it; a friend is in it from the start.
            if (opponents.mode == GameMode.CLUB) listOf(stored) else players,
            now,
            pool
        )
        swipeRoundCardRepository.insert(movies.mapIndexed { position, movie -> SwipeRoundCard(match, position, movie) })
        match
    }

    /**
     * Starts a club's round on a deck given: a movie night's candidates,
     * which the members swipe to choose the night's movie, joining as they
     * swipe. The host, a member, is in it from the start.
     */
    fun startRoundWith(host: User, club: Club, movies: List<Movie>, now: Instant): GameMatch {
        val (match, _) = matchService.start(
            GameMatch(game = Game.SWIPE, mode = GameMode.CLUB, club = club, host = host, createdAt = now,
                closesAt = now.plus(MatchService.ROUND), endedAt = null),
            listOf(host),
            now
        )
        swipeRoundCardRepository.insert(movies.take(ROUND_CARDS).mapIndexed { position, movie -> SwipeRoundCard(match, position, movie) })
        return match
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
        // How long each player took over each poster, the first poster's time evened out (decisionTimes).
        val times = swipes.groupBy { it.user.entityId() }.mapValues { (_, theirs) -> decisionTimes(theirs) }
        val results = cards.mapNotNull { movie ->
            val by = wanted[movie.id].orEmpty().mapNotNull(users::get)
            val decided = by.mapNotNull { times[it.id]?.get(movie.id) }
            RoundResult(movie, by, decided.takeIf { it.isNotEmpty() }?.average()?.toInt()).takeIf { by.isNotEmpty() }
        }
        val viewerDone = players.firstOrNull { it.user.id == user.id }?.finished == true
        val byMovie = cards.associateBy { it.id }
        val byPlayer = players.map { player ->
            val theirs = swipes.filter { it.user.entityId() == player.user.id }
            val wants = theirs.filter { it.way == SwipeWay.WANT }
                .map { TimedMovie(byMovie.getValue(it.movie.entityId()), times.getValue(player.user.id)[it.movie.entityId()] ?: Int.MAX_VALUE) }
                .sortedBy { it.decidedMs }
            PlayerSwipes(player, theirs.size, player.finished, shown = player.finished && (viewerDone || over), wanted = wants)
        }.sortedWith(compareByDescending<PlayerSwipes> { it.player.user.id == user.id }.thenByDescending { it.finished })
        SwipeRoundView(
            match = match,
            cards = if (over) emptyList() else cardsOf(cards.filter { it.id !in mine }),
            players = players,
            swiped = swipes.groupingBy { it.user.entityId() }.eachCount(),
            deckSize = cards.size,
            results = when (match.mode) {
                // With a friend: the matches, the movies both want, the ones decided quickest first.
                GameMode.FRIEND -> results.filter { it.wantedBy.size == players.size }.sortedBy { it.decidedMs ?: Int.MAX_VALUE }
                // With a club: the most wanted, the ballot of a movie night; a tie goes to the one decided quickest.
                else -> results.sortedWith(compareByDescending<RoundResult> { it.wantedBy.size }.thenBy { it.decidedMs ?: Int.MAX_VALUE }).take(BALLOT)
            },
            byPlayer = byPlayer,
            over = over,
            hoursLeft = match.closesAt?.let { Duration.between(now, it).toHours().coerceAtLeast(0) }
        )
    }

    /** Swipes a movie of the round; a club's member joins the round with their first swipe. Nothing once it is closed. */
    /**
     * How long a player took over each poster of a round, by movie: as the
     * page measured it, except the first poster, which took the page's
     * loading and the rules' reading too: it gets the median of the others,
     * so a slow start costs nobody their quickest yes.
     */
    private fun decisionTimes(swipes: List<SwipeRoundSwipe>): Map<String, Int> {
        val timed = swipes.filter { it.decidedMs != null }.sortedBy { it.swipedAt }
        if (timed.isEmpty()) return emptyMap()
        val rest = timed.drop(1).map { it.decidedMs!! }.sorted()
        val median = if (rest.isEmpty()) timed.first().decidedMs!! else rest[rest.size / 2]
        return timed.mapIndexed { i, swipe -> swipe.movie.entityId() to (if (i == 0) minOf(swipe.decidedMs!!, median) else swipe.decidedMs!!) }.toMap()
    }

    suspend fun swipeInRound(user: User, matchId: Long, movieId: String, way: SwipeWay, now: Instant = Instant.now(), decidedMs: Int? = null) = transaction {
        val match = matchService.visibleMatch(user, matchId)?.takeIf { it.game == Game.SWIPE && !it.isOver(now) } ?: return@transaction
        val movie = swipeRoundCardRepository.findCards(match).map { it.movie }.firstOrNull { it.id == movieId } ?: return@transaction
        val stored = userRepository.touch(user, now)
        val player = gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = stored.id))
            ?: (if (match.mode == GameMode.CLUB) GamePlayer(match, stored, gamePlayerRepository.findPlayers(match).size, now).also(gamePlayerRepository::insert) else null)
            ?: return@transaction
        val swipe = SwipeRoundSwipe(match, stored, movie, way, now, decidedMs)
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
        /** The quickest yeses the deck on one's own shows. */
        private const val QUICKEST = 6
        /** The longest a swipe counts as taking: anything past it was a poster left open, not a decision. */
        const val LONGEST_MS = 120_000
    }
}
