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
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.DailyPlayRepository
import st.orm.demo.imdb.repository.DailyPuzzleRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.transaction
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

/**
 * The daily puzzles besides the quiz, the same for everyone of a taste on a
 * day: Odd One Out, ten sets of four movies with one that does not belong,
 * and Movie Connections, sixteen movies in four groups of four. Both are
 * made by [PuzzleFactory] from the best-known movies the player's filter
 * lets through, the first time someone of that taste opens them that day,
 * and kept ([DailyPuzzle]). Anyone plays; friends and club members see how
 * the others did, once they finished themselves.
 */
class DailyPuzzleService(
    private val puzzleFactory: PuzzleFactory,
    private val dailyPuzzleRepository: DailyPuzzleRepository,
    private val dailyPlayRepository: DailyPlayRepository,
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val userRepository: UserRepository
) {

    /* ── Odd One Out ──────────────────────────────────────────────── */

    suspend fun oddOf(user: User, today: LocalDate = today()): OddView = transaction {
        val play = dailyPlayRepository.findById(DailyPlayPk(userId = user.id, puzzleOn = today, game = DailyGame.ODD_ONE_OUT))
        val puzzles = puzzleOf(today, DailyGame.ODD_ONE_OUT, play?.filterKey, user.movieFilter).data.odd
        val answers = play?.answers.orEmpty()
        val movies = movieRepository.findAllById(puzzles.flatMap { it.movies }).associateBy { it.id }
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
    suspend fun answerOdd(user: User, movieId: String, today: LocalDate = today(), now: Instant = Instant.now()) = transaction {
        val stored = userRepository.touch(user, now)
        val play = playOf(stored, today, DailyGame.ODD_ONE_OUT, now)
        val puzzles = puzzleOf(today, DailyGame.ODD_ONE_OUT, play.filterKey, stored.movieFilter).data.odd
        if (play.answers.size >= puzzles.size) return@transaction
        val puzzle = puzzles[play.answers.size]
        if (movieId !in puzzle.movies) return@transaction
        val answers = play.answers + movieId
        save(play.copy(answers = answers, score = play.score + if (movieId == puzzle.odd) 1 else 0, finished = answers.size >= puzzles.size, updatedAt = now))
    }

    /* ── Movie Connections ────────────────────────────────────────── */

    suspend fun connectionsOf(user: User, today: LocalDate = today()): ConnectionsView = transaction {
        val play = dailyPlayRepository.findById(DailyPlayPk(userId = user.id, puzzleOn = today, game = DailyGame.CONNECTIONS))
        val groups = puzzleOf(today, DailyGame.CONNECTIONS, play?.filterKey, user.movieFilter).data.groups
        val found = play?.answers.orEmpty()
        val movies = movieRepository.findAllById(groups.flatMap { it.movies }).associateBy { it.id }
        fun shown(group: ConnectionGroup) = FoundGroup(group.trait.label, group.trait.level, group.movies.mapNotNull(movies::get))
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
        val groups = puzzleOf(today, DailyGame.CONNECTIONS, play.filterKey, stored.movieFilter).data.groups
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

    /* ── Shared ───────────────────────────────────────────────────── */

    /**
     * A day's puzzles of a game for a taste: the ones made for it, or, the
     * first time someone of that taste opens the game that day, made from
     * the best-known movies the taste lets through, at random by the day,
     * the game and the taste. A play keeps the taste it started with ([filterKey]).
     */
    private fun puzzleOf(day: LocalDate, game: DailyGame, filterKey: String?, filter: MovieFilter?): DailyPuzzle {
        val key = filterKey ?: filter?.key.orEmpty()
        val id = DailyPuzzlePk(puzzleOn = day, game = game, filterKey = key)
        dailyPuzzleRepository.findById(id)?.let { return it }
        val random = Random(day.toEpochDay() * 31 + game.ordinal * 7 + key.hashCode())
        fun make(filter: MovieFilter?): DailyPuzzleData? {
            val movies = ratingRepository.findBestKnown(filter, MINIMUM_VOTES, POOL).map { it.movie }
            val traits = puzzleFactory.traitsOf(movies)
            return when (game) {
                DailyGame.ODD_ONE_OUT -> puzzleFactory.oddOneOut(movies, traits, random, ODD_PUZZLES).takeIf { it.size == ODD_PUZZLES }?.let { DailyPuzzleData(odd = it) }
                DailyGame.CONNECTIONS -> puzzleFactory.connections(movies, traits, random)?.let { DailyPuzzleData(groups = it) }
            }
        }
        // A taste too narrow for a day's puzzles gets everybody's.
        val data = make(filter) ?: make(null) ?: error("No $game puzzles could be made for $day")
        val puzzle = DailyPuzzle(id, data)
        // Two players of a taste opening the game at once make the same puzzles, so the second write changes nothing.
        dailyPuzzleRepository.upsert(puzzle)
        return puzzle
    }

    /** The player's game of the day, started now when they have none. */
    private fun playOf(user: User, day: LocalDate, game: DailyGame, now: Instant): DailyPlay =
        dailyPlayRepository.findById(DailyPlayPk(userId = user.id, puzzleOn = day, game = game))
            ?: DailyPlay(DailyPlayPk(userId = user.id, puzzleOn = day, game = game), user, user.movieFilter?.key.orEmpty(), emptyList(), 0, 0, false, now)

    private fun save(play: DailyPlay) {
        if (dailyPlayRepository.existsById(play.id)) dailyPlayRepository.update(play) else dailyPlayRepository.insert(play)
    }

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
        const val ODD_PUZZLES = 10
        const val MAX_MISTAKES = 4
        private const val POOL = 400
        private const val MINIMUM_VOTES = 25_000
        private val FIRST_DAY: LocalDate = LocalDate.of(2026, 10, 27)
    }
}
