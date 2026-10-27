package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.model.DailyGame
import st.orm.demo.imdb.model.DailyPlay
import st.orm.demo.imdb.model.DailyPlayPk
import st.orm.demo.imdb.model.DailyPuzzle
import st.orm.demo.imdb.model.DailyPuzzleData
import st.orm.demo.imdb.model.DailyPuzzlePk
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.DailyPlayRepository
import st.orm.demo.imdb.repository.DailyPuzzleRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.ref
import st.orm.template.transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** A row of a daily game's board: a player, their score as the board says it, and whether it is the visitor. */
data class BoardRow(val name: String, val avatarUrl: String?, val score: String, val you: Boolean)

/** A board: the visitor's friends, or a club, and how each who finished today did. */
data class Board(val title: String, val rows: List<BoardRow>)

/** An Odd One Out puzzle answered: its movies, the odd one, the one picked, and what the other three share. */
data class OddResult(val movies: List<Movie>, val odd: Movie, val picked: Movie?, val label: String) {
    val right: Boolean get() = picked?.id == odd.id
}

/**
 * A day's Odd One Out for a player: its number, the puzzle at hand, the
 * one just answered with its reveal, the score, and once all are answered,
 * every puzzle's reveal and the boards.
 */
data class OddView(
    val number: Long,
    val count: Int,
    val index: Int,
    val current: List<Movie>,
    val last: OddResult?,
    val score: Int,
    val finished: Boolean,
    val results: List<OddResult>,
    val boards: List<Board>
)

/** A group of a Connections grid, found or shown at the end: its movies and what they share, with its level for its colour. */
data class FoundGroup(val label: String, val level: Int, val movies: List<Movie>) {
    /** The movies' titles in a line: "Inception · Memento · Tenet · Dunkirk". */
    val titles: String get() = movies.joinToString(" · ") { it.primaryTitle }
}

/**
 * A day's Movie Connections for a player: the posters still to group, the
 * groups found, the mistakes, and once over, every group and the boards.
 */
data class ConnectionsView(
    val number: Long,
    val remaining: List<Movie>,
    val found: List<FoundGroup>,
    val mistakes: Int,
    val finished: Boolean,
    val won: Boolean,
    val all: List<FoundGroup>,
    val boards: List<Board>
) {
    val mistakesLeft: Int get() = DailyPuzzleService.MAX_MISTAKES - mistakes
}

/** What came of a guess of four. */
enum class GridGuess { RIGHT, ONE_AWAY, WRONG, NOT_FOUR }

/** A Face/Off answered: the two movies with their ratings, the higher one, and the one picked. */
data class DuelResult(val movies: List<Movie>, val higher: Movie, val picked: Movie?, val ratings: Map<String, BigDecimal>) {
    val right: Boolean get() = picked?.id == higher.id
    fun ratingOf(movie: Movie): BigDecimal = ratings.getValue(movie.id)
}

/** A day's Face/Off for a player: the pair at hand, the one just answered, the score, and once over, every pair and the boards. */
data class DuelView(
    val number: Long,
    val count: Int,
    val index: Int,
    val current: List<Movie>,
    val last: DuelResult?,
    val score: Int,
    val finished: Boolean,
    val results: List<DuelResult>,
    val boards: List<Board>
)

/** A line-up at hand: the movie, the part, and the five people. */
data class LineUpCase(val movie: Movie, val character: String, val suspects: List<Person>)

/** A line-up answered: who played the part, and who was picked. */
data class LineUpResult(val movie: Movie, val character: String, val culprit: Person, val picked: Person?) {
    val right: Boolean get() = picked?.id == culprit.id
}

/** A day's The Usual Suspects for a player: the line-up at hand, the one just answered, the score, and once over, every line-up and the boards. */
data class LineUpView(
    val number: Long,
    val count: Int,
    val index: Int,
    val current: LineUpCase?,
    val last: LineUpResult?,
    val score: Int,
    val finished: Boolean,
    val results: List<LineUpResult>,
    val boards: List<Board>
)

/** A Memento series at hand: the movies still to place, as shown, and the ones placed so far, first to last. */
data class SeriesAtHand(val remaining: List<Movie>, val picked: List<Movie>)

/** A series placed: its movies in the order they came out, and in the order they were placed. */
data class SeriesResult(val order: List<Movie>, val picked: List<Movie>) {
    val right: Boolean get() = order.map { it.id } == picked.map { it.id }
}

/** A day's Memento for a player: the series at hand, the one just placed, the score, and once over, every series and the boards. */
data class SeriesView(
    val number: Long,
    val count: Int,
    val index: Int,
    val current: SeriesAtHand?,
    val last: SeriesResult?,
    val score: Int,
    val finished: Boolean,
    val results: List<SeriesResult>,
    val boards: List<Board>
)

/** An Amadeus puzzle at hand: the movie, and the composers to choose from. */
data class Score(val movie: Movie, val composers: List<Person>)

/** An Amadeus puzzle answered: who wrote the score, and who was picked. */
data class ScoreResult(val movie: Movie, val composer: Person, val picked: Person?) {
    val right: Boolean get() = picked?.id == composer.id
}

/** A day's Amadeus for a player: the movie at hand, the one just answered, the score, and once over, every movie and the boards. */
data class ScoreView(
    val number: Long,
    val count: Int,
    val index: Int,
    val current: Score?,
    val last: ScoreResult?,
    val score: Int,
    val finished: Boolean,
    val results: List<ScoreResult>,
    val boards: List<Board>
)

/**
 * The daily puzzles besides the quiz, the same for everyone of a taste on a
 * day: Odd One Out, ten sets of four movies with one that does not belong;
 * Movie Connections, sixteen movies in four groups of four; Face/Off, ten
 * pairs, which is rated higher; The Usual Suspects, ten parts, who played
 * them; and Memento, series of movies to put in order of release. Amadeus,
 * who wrote the score, is made of the player's own movies, so it is theirs
 * alone. All are made by [PuzzleFactory] from the best-known movies the
 * player's filter lets through, the first time someone of that taste opens
 * them that day, and kept ([DailyPuzzle]). Anyone plays; friends and club
 * members see how the others did, once they finished themselves.
 */
class DailyPuzzleService(
    private val puzzleFactory: PuzzleFactory,
    private val dailyPuzzleRepository: DailyPuzzleRepository,
    private val dailyPlayRepository: DailyPlayRepository,
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val userRepository: UserRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val personRepository: PersonRepository
) {

    /* ── Odd One Out ──────────────────────────────────────────────── */

    suspend fun oddOf(user: User, today: LocalDate = today()): OddView = transaction {
        val play = playIfAny(user, today, DailyGame.ODD_ONE_OUT)
        val puzzles = puzzleOf(user, today, DailyGame.ODD_ONE_OUT, play).data.odd
        val answers = play?.answers.orEmpty()
        val movies = moviesOf(puzzles.flatMap { it.movies })
        fun resultOf(i: Int) = puzzles[i].let { p -> OddResult(p.movies.mapNotNull(movies::get), movies.getValue(p.odd), answers.getOrNull(i)?.let(movies::get), p.trait.label) }
        val finished = answers.size >= puzzles.size
        OddView(
            number = numberOf(today),
            count = puzzles.size,
            index = answers.size,
            current = if (finished) emptyList() else puzzles[answers.size].movies.mapNotNull(movies::get),
            last = if (answers.isEmpty()) null else resultOf(answers.size - 1),
            score = play?.score ?: 0,
            finished = finished,
            results = if (finished) puzzles.indices.map(::resultOf) else emptyList(),
            boards = if (finished) boardsOf(user, today, DailyGame.ODD_ONE_OUT) { "${it.score}/${puzzles.size}" } else emptyList()
        )
    }

    /** Picks the odd one of the puzzle at hand; nothing once all are answered. */
    suspend fun answerOdd(user: User, movieId: String, today: LocalDate = today(), now: Instant = Instant.now()) =
        pick(user, DailyGame.ODD_ONE_OUT, movieId, today, now, { it.odd.map { p -> p.movies to p.odd } })

    /* ── Movie Connections ────────────────────────────────────────── */

    suspend fun connectionsOf(user: User, today: LocalDate = today()): ConnectionsView = transaction {
        val play = playIfAny(user, today, DailyGame.CONNECTIONS)
        val groups = puzzleOf(user, today, DailyGame.CONNECTIONS, play).data.groups
        val found = play?.answers.orEmpty()
        val movies = moviesOf(groups.flatMap { it.movies })
        // A group's colour is its place in the grid, the directors first.
        fun shown(group: ConnectionGroup) = FoundGroup(group.trait.label, groups.indexOf(group) + 1, group.movies.mapNotNull(movies::get))
        val foundGroups = found.mapNotNull { key -> groups.firstOrNull { it.trait.key == key } }
        val mistakes = play?.mistakes ?: 0
        val finished = foundGroups.size == groups.size || mistakes >= MAX_MISTAKES
        val left = groups.filter { it !in foundGroups }.flatMap { it.movies }
        ConnectionsView(
            number = numberOf(today),
            // The posters still to group, in an order of the day, the same on every visit.
            remaining = left.shuffled(Random(today.toEpochDay())).mapNotNull(movies::get),
            found = foundGroups.map(::shown),
            mistakes = mistakes,
            finished = finished,
            won = foundGroups.size == groups.size,
            all = if (finished) groups.map(::shown) else emptyList(),
            boards = if (finished) boardsOf(user, today, DailyGame.CONNECTIONS) { play ->
                if (play.answers.size == groups.size) "${play.mistakes} ${if (play.mistakes == 1) "mistake" else "mistakes"}" else "${play.answers.size}/4 groups"
            } else emptyList()
        )
    }

    /** A guess of four of the posters: a group found, one away from one, or wrong, a mistake either way. */
    suspend fun guessConnections(user: User, movieIds: Set<String>, today: LocalDate = today(), now: Instant = Instant.now()): GridGuess = transaction {
        if (movieIds.size != 4) return@transaction GridGuess.NOT_FOUR
        val stored = userRepository.touch(user, now)
        val play = playOf(stored, today, DailyGame.CONNECTIONS, now)
        val groups = puzzleOf(stored, today, DailyGame.CONNECTIONS, play).data.groups
        if (play.finished) return@transaction GridGuess.WRONG
        val open = groups.filter { it.trait.key !in play.answers }
        val hit = open.firstOrNull { it.movies.toSet() == movieIds }
        val result = when {
            hit != null -> GridGuess.RIGHT
            open.any { it.movies.count { id -> id in movieIds } == 3 } -> GridGuess.ONE_AWAY
            else -> GridGuess.WRONG
        }
        val answers = if (hit != null) play.answers + hit.trait.key else play.answers
        val mistakes = if (hit != null) play.mistakes else play.mistakes + 1
        save(play.copy(answers = answers, score = answers.size, mistakes = mistakes, finished = answers.size == groups.size || mistakes >= MAX_MISTAKES, updatedAt = now))
        result
    }

    /* ── Face/Off ─────────────────────────────────────────────────── */

    suspend fun duelsOf(user: User, today: LocalDate = today()): DuelView = transaction {
        val play = playIfAny(user, today, DailyGame.FACE_OFF)
        val duels = puzzleOf(user, today, DailyGame.FACE_OFF, play).data.duels
        val answers = play?.answers.orEmpty()
        val movies = moviesOf(duels.flatMap { it.movies })
        val ratings = ratingRepository.findByMovies(movies.values.map { it.ref() }).associate { it.movie.id to it.averageRating }
        fun resultOf(i: Int) = duels[i].let { d -> DuelResult(d.movies.mapNotNull(movies::get), movies.getValue(d.higher), answers.getOrNull(i)?.let(movies::get), ratings) }
        val finished = answers.size >= duels.size
        DuelView(
            number = numberOf(today),
            count = duels.size,
            index = answers.size,
            current = if (finished) emptyList() else duels[answers.size].movies.mapNotNull(movies::get),
            last = if (answers.isEmpty()) null else resultOf(answers.size - 1),
            score = play?.score ?: 0,
            finished = finished,
            results = if (finished) duels.indices.map(::resultOf) else emptyList(),
            boards = if (finished) boardsOf(user, today, DailyGame.FACE_OFF) { "${it.score}/${duels.size}" } else emptyList()
        )
    }

    /** Picks the higher rated of the pair at hand; nothing once all are answered. */
    suspend fun answerDuel(user: User, movieId: String, today: LocalDate = today(), now: Instant = Instant.now()) =
        pick(user, DailyGame.FACE_OFF, movieId, today, now, { it.duels.map { d -> d.movies to d.higher } })

    /* ── The Usual Suspects ───────────────────────────────────────── */

    suspend fun lineUpsOf(user: User, today: LocalDate = today()): LineUpView = transaction {
        val play = playIfAny(user, today, DailyGame.USUAL_SUSPECTS)
        val lineUps = puzzleOf(user, today, DailyGame.USUAL_SUSPECTS, play).data.lineUps
        val answers = play?.answers.orEmpty()
        val movies = moviesOf(lineUps.map { it.movie })
        val people = peopleOf(lineUps.flatMap { it.suspects })
        fun resultOf(i: Int) = lineUps[i].let { l -> LineUpResult(movies.getValue(l.movie), l.character, people.getValue(l.culprit), answers.getOrNull(i)?.let(people::get)) }
        val finished = answers.size >= lineUps.size
        LineUpView(
            number = numberOf(today),
            count = lineUps.size,
            index = answers.size,
            current = if (finished) null else lineUps[answers.size].let { l -> LineUpCase(movies.getValue(l.movie), l.character, l.suspects.mapNotNull(people::get)) },
            last = if (answers.isEmpty()) null else resultOf(answers.size - 1),
            score = play?.score ?: 0,
            finished = finished,
            results = if (finished) lineUps.indices.map(::resultOf) else emptyList(),
            boards = if (finished) boardsOf(user, today, DailyGame.USUAL_SUSPECTS) { "${it.score}/${lineUps.size}" } else emptyList()
        )
    }

    /** Points at one of the line-up at hand; nothing once all are answered. */
    suspend fun answerLineUp(user: User, personId: String, today: LocalDate = today(), now: Instant = Instant.now()) =
        pick(user, DailyGame.USUAL_SUSPECTS, personId, today, now, { it.lineUps.map { l -> l.suspects to l.culprit } })

    /* ── Memento ──────────────────────────────────────────────────── */

    suspend fun seriesOf(user: User, today: LocalDate = today()): SeriesView = transaction {
        val play = playIfAny(user, today, DailyGame.MEMENTO)
        val series = puzzleOf(user, today, DailyGame.MEMENTO, play).data.series
        val answers = play?.answers.orEmpty()
        val movies = moviesOf(series.flatMap { it.movies })
        // The answers are the movies placed, series after series: each series' own are the ones after those before it.
        val placed = series.runningFold(0) { from, s -> from + s.movies.size }.zipWithNext().map { (from, until) -> answers.drop(from).take(until - from) }
        val done = placed.withIndex().count { (i, picks) -> picks.size == series[i].movies.size }
        fun resultOf(i: Int) = SeriesResult(series[i].order.mapNotNull(movies::get), placed[i].mapNotNull(movies::get))
        val finished = done >= series.size
        SeriesView(
            number = numberOf(today),
            count = series.size,
            index = done,
            current = if (finished) null else SeriesAtHand(series[done].movies.filter { it !in placed[done] }.mapNotNull(movies::get), placed[done].mapNotNull(movies::get)),
            last = if (done == 0) null else resultOf(done - 1),
            score = play?.score ?: 0,
            finished = finished,
            results = if (finished) series.indices.map(::resultOf) else emptyList(),
            boards = if (finished) boardsOf(user, today, DailyGame.MEMENTO) { "${it.score}/${series.size}" } else emptyList()
        )
    }

    /** Places the next movie of the series at hand; a series placed in the order it came out scores a point. */
    suspend fun placeInSeries(user: User, movieId: String, today: LocalDate = today(), now: Instant = Instant.now()) = transaction {
        val stored = userRepository.touch(user, now)
        val play = playOf(stored, today, DailyGame.MEMENTO, now)
        val series = puzzleOf(stored, today, DailyGame.MEMENTO, play).data.series
        var from = 0
        val atHand = series.firstOrNull { s -> (play.answers.size < from + s.movies.size).also { if (!it) from += s.movies.size } } ?: return@transaction
        val placed = play.answers.drop(from)
        if (movieId !in atHand.movies || movieId in placed) return@transaction
        val answers = play.answers + movieId
        val complete = placed.size + 1 == atHand.movies.size
        val right = complete && answers.drop(from) == atHand.order
        save(play.copy(answers = answers, score = play.score + if (right) 1 else 0, finished = answers.size >= series.sumOf { it.movies.size }, updatedAt = now))
    }

    /* ── Amadeus ──────────────────────────────────────────────────── */

    suspend fun scoresOf(user: User, today: LocalDate = today()): ScoreView = transaction {
        val play = playIfAny(user, today, DailyGame.AMADEUS)
        val scores = puzzleOf(user, today, DailyGame.AMADEUS, play).data.scores
        val answers = play?.answers.orEmpty()
        val movies = moviesOf(scores.map { it.movie })
        val people = peopleOf(scores.flatMap { it.composers })
        fun resultOf(i: Int) = scores[i].let { s -> ScoreResult(movies.getValue(s.movie), people.getValue(s.composer), answers.getOrNull(i)?.let(people::get)) }
        val finished = answers.size >= scores.size
        ScoreView(
            number = numberOf(today),
            count = scores.size,
            index = answers.size,
            current = if (finished) null else scores[answers.size].let { s -> Score(movies.getValue(s.movie), s.composers.mapNotNull(people::get)) },
            last = if (answers.isEmpty()) null else resultOf(answers.size - 1),
            score = play?.score ?: 0,
            finished = finished,
            results = if (finished) scores.indices.map(::resultOf) else emptyList(),
            boards = if (finished) boardsOf(user, today, DailyGame.AMADEUS) { "${it.score}/${scores.size}" } else emptyList()
        )
    }

    /** Names the composer of the movie at hand; nothing once all are answered. */
    suspend fun answerScore(user: User, personId: String, today: LocalDate = today(), now: Instant = Instant.now()) =
        pick(user, DailyGame.AMADEUS, personId, today, now, { it.scores.map { s -> s.composers to s.composer } })

    /* ── Shared ───────────────────────────────────────────────────── */

    /**
     * An answer to the puzzle at hand of a game of one pick per puzzle: the
     * pick must be one of the puzzle's choices, and scores a point when it
     * is the answer. Nothing once all are answered. [choices] reads the
     * game's puzzles out of the day's data, each as its choices and its answer.
     */
    private suspend fun pick(user: User, game: DailyGame, id: String, today: LocalDate, now: Instant, choices: (DailyPuzzleData) -> List<Pair<List<String>, String>>) = transaction {
        val stored = userRepository.touch(user, now)
        val play = playOf(stored, today, game, now)
        val puzzles = choices(puzzleOf(stored, today, game, play).data)
        if (play.answers.size >= puzzles.size) return@transaction
        val (options, answer) = puzzles[play.answers.size]
        if (id !in options) return@transaction
        val answers = play.answers + id
        save(play.copy(answers = answers, score = play.score + if (id == answer) 1 else 0, finished = answers.size >= puzzles.size, updatedAt = now))
    }

    /**
     * A day's puzzles of a game for a player: the ones made for their taste,
     * or for Amadeus for themselves, or, the first time the game is opened
     * that day, made from the best-known movies the taste lets through, at
     * random by the day, the game and the taste. A play keeps the taste it
     * started with ([DailyPlay.filterKey]).
     */
    private fun puzzleOf(user: User, day: LocalDate, game: DailyGame, play: DailyPlay?): DailyPuzzle {
        val key = play?.filterKey ?: keyOf(user, game)
        val id = DailyPuzzlePk(puzzleOn = day, game = game, filterKey = key)
        dailyPuzzleRepository.findById(id)?.let { return it }
        val random = Random(day.toEpochDay() * 31 + game.ordinal * 7 + key.hashCode())
        // A taste too narrow for a day's puzzles gets everybody's.
        val data = made(user, game, user.movieFilter, random) ?: made(user, game, null, random) ?: error("No $game puzzles could be made for $day")
        val puzzle = DailyPuzzle(id, data)
        // Two players of a taste opening the game at once make the same puzzles, so the second write changes nothing.
        dailyPuzzleRepository.upsert(puzzle)
        return puzzle
    }

    /** A day's puzzles of a game, from the best-known movies of a taste; null when the taste has too few for them. */
    private fun made(user: User, game: DailyGame, filter: MovieFilter?, random: Random): DailyPuzzleData? {
        val ratings = ratingRepository.findBestKnown(filter, MINIMUM_VOTES, POOL)
        val movies = ratings.map { it.movie }
        return when (game) {
            DailyGame.ODD_ONE_OUT -> puzzleFactory.oddOneOut(movies, puzzleFactory.traitsOf(movies), random, TEN).takeIf { it.size == TEN }?.let { DailyPuzzleData(odd = it) }
            DailyGame.CONNECTIONS -> puzzleFactory.connections(movies, puzzleFactory.traitsOf(movies), random)?.let { DailyPuzzleData(groups = it) }
            DailyGame.FACE_OFF -> puzzleFactory.faceOffs(ratings, random, TEN).takeIf { it.size == TEN }?.let { DailyPuzzleData(duels = it) }
            DailyGame.USUAL_SUSPECTS -> puzzleFactory.lineUps(movies, puzzleFactory.creditsOf(movies), random, TEN).takeIf { it.size == TEN }?.let { DailyPuzzleData(lineUps = it) }
            DailyGame.MEMENTO -> puzzleFactory.series(movies, puzzleFactory.creditsOf(movies), random, SERIES_A_DAY).takeIf { it.size >= SERIES_MINIMUM }?.let { DailyPuzzleData(series = it) }
            DailyGame.AMADEUS -> {
                // The player's own movies, the latest watched, filled up from the best known when they have too few with a composer.
                val own = movieSeenRepository.findMostRecent(user, OWN_POOL).map { it.movie }
                val credits = puzzleFactory.creditsOf((own + movies).distinctBy { it.id })
                val scores = puzzleFactory.scores(own, credits, random, TEN)
                val ownIds = own.map { it.id }.toSet()
                (scores + puzzleFactory.scores(movies.filter { it.id !in ownIds }, credits, random, TEN - scores.size)).takeIf { it.size == TEN }?.let { DailyPuzzleData(scores = it) }
            }
        }
    }

    /** Whose puzzles a player gets: their taste's, or for Amadeus their own. */
    private fun keyOf(user: User, game: DailyGame): String =
        if (game == DailyGame.AMADEUS) "user:${user.id}" else user.movieFilter?.key.orEmpty()

    private fun playIfAny(user: User, day: LocalDate, game: DailyGame): DailyPlay? =
        dailyPlayRepository.findById(DailyPlayPk(userId = user.id, puzzleOn = day, game = game))

    /** The player's game of the day, started now when they have none. */
    private fun playOf(user: User, day: LocalDate, game: DailyGame, now: Instant): DailyPlay =
        playIfAny(user, day, game) ?: DailyPlay(DailyPlayPk(userId = user.id, puzzleOn = day, game = game), user, keyOf(user, game), emptyList(), 0, 0, false, now)

    private fun save(play: DailyPlay) {
        if (dailyPlayRepository.existsById(play.id)) dailyPlayRepository.update(play) else dailyPlayRepository.insert(play)
    }

    private fun moviesOf(ids: List<String>): Map<String, Movie> = movieRepository.findAllById(ids.distinct()).associateBy { it.id }

    private fun peopleOf(ids: List<String>): Map<String, Person> = personRepository.findAllById(ids.distinct()).associateBy { it.id }

    /**
     * How the visitor's friends and clubs did today, a board each with
     * anyone else on it: friends by name, club members as far as they share
     * it, the best first, each score as [score] says it.
     */
    private fun boardsOf(user: User, day: LocalDate, game: DailyGame, score: (DailyPlay) -> String): List<Board> {
        val friends = friendshipRepository.findFriends(user).map { it.friend }
        val members = clubMemberRepository.findMembers(clubMemberRepository.findClubs(user).map { it.club })
        val plays = dailyPlayRepository.findFinished((friends + members.map { it.user } + user).distinctBy { it.id }, day, game).associateBy { it.user.id }
        val best = compareByDescending<Pair<BoardRow, DailyPlay>> { it.second.score }.thenBy { it.second.mistakes }
        fun rowsOf(people: List<Pair<User, String?>>) = people.mapNotNull { (person, name) ->
            plays[person.id]?.let { play ->
                val you = person.id == user.id
                BoardRow(if (you) "You" else name ?: "Someone", person.avatarUrl.takeIf { you || name == person.name }, score(play), you) to play
            }
        }.sortedWith(best).map { it.first }
        val friendBoard = rowsOf((friends + user).map { it to it.name }).takeIf { rows -> rows.any { !it.you } }?.let { Board("Your friends", it) }
        val clubBoards = members.groupBy { it.club }.mapNotNull { (club, inClub) ->
            rowsOf(inClub.map { member: ClubMember -> member.user to (member.user.name.takeIf { member.sharesName } ?: "A member") })
                .takeIf { rows -> rows.any { !it.you } }?.let { Board(club.name, it) }
        }.sortedBy { it.title }
        return listOfNotNull(friendBoard) + clubBoards
    }

    private fun numberOf(day: LocalDate): Long = ChronoUnit.DAYS.between(FIRST_DAY, day) + 1

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    companion object {
        /** The puzzles a day of the games of one pick per puzzle. */
        const val TEN = 10
        const val ODD_PUZZLES = TEN
        const val MAX_MISTAKES = 4
        /** The series a day of Memento, as far as the pool has them, three at least. */
        const val SERIES_A_DAY = 5
        private const val SERIES_MINIMUM = 3
        private const val POOL = 400
        /** The player's own movies Amadeus is made of: the latest watched. */
        private const val OWN_POOL = 150
        private const val MINIMUM_VOTES = 25_000
        private val FIRST_DAY: LocalDate = LocalDate.of(2026, 10, 27)
    }
}
