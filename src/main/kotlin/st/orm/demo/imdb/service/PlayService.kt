package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant

/**
 * One of the visitor's games on the Play page: the match, who it is with,
 * how it stands, and whether it waits on them ([yourMove]).
 */
data class GameEntry(val match: GameMatch, val with: String, val status: String, val yourMove: Boolean) {
    val title: String get() = if (match.game == Game.CHAIN) "Movie Chain" else "Higher or Lower"
}

/**
 * The Play page: the visitor's games, the ones waiting on them first, who
 * they can start one with, and the posters of the movies of their taste,
 * for the wall across the top.
 */
data class PlayView(val games: List<GameEntry>, val company: Company, val posters: List<Movie>) {
    /** The wall's rows of posters, a third of them each. */
    val posterRows: List<List<Movie>> get() = posters.chunked(((posters.size + 2) / 3).coerceAtLeast(1))
}

/**
 * The Play page's list of the visitor's games: the matches they play, of
 * the last weeks, and the rounds of their clubs they have not played yet,
 * each saying how it stands; the ones waiting on them first.
 */
class PlayService(
    private val matchService: MatchService,
    private val gameMatchRepository: GameMatchRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val chainService: ChainService,
    private val ratingRepository: RatingRepository
) {

    suspend fun playOf(user: User, now: Instant = Instant.now()): PlayView = transaction(readOnly = true) {
        val company = matchService.companyOf(user)
        val mine = gamePlayerRepository.findRecent(user, now.minus(RECENT), LIMIT)
        val joined = mine.map { it.match.id }.toSet()
        val clubRounds = gameMatchRepository.findOpen(company.clubs, now, LIMIT).filter { it.id !in joined }
        val entries = mine.map { entryOf(user, it.match, it, now) } +
            clubRounds.map { GameEntry(it, withOf(user, it, emptyList()), "Your turn: the round is open", yourMove = true) }
        PlayView(
            entries.sortedWith(compareByDescending<GameEntry> { it.yourMove }.thenByDescending { it.match.createdAt }),
            company,
            ratingRepository.findBestKnown(user.movieFilter, POSTER_VOTES, POSTER_POOL).shuffled().take(POSTERS).map { it.movie }
        )
    }

    private fun entryOf(user: User, match: GameMatch, me: GamePlayer, now: Instant): GameEntry {
        val players = gamePlayerRepository.findPlayers(match)
        val with = withOf(user, match, players)
        return when (match.game) {
            Game.HIGHER_LOWER -> when {
                !me.finished && !match.isOver(now) -> GameEntry(match, with, if (me.score == 0) "Your turn" else "Your turn: a streak of ${me.score} going", yourMove = true)
                match.isOver(now) || match.mode == GameMode.SOLO -> GameEntry(match, with, resultOf(user, players) { "a streak of $it" }, yourMove = false)
                else -> GameEntry(match, with, "You scored ${me.score}; waiting for the others", yourMove = false)
            }
            Game.CHAIN -> {
                val turn = chainService.turnIn(match, players, now)
                when {
                    turn == null -> GameEntry(match, with, resultOf(user, players) { if (it == 1) "1 point" else "$it points" }, yourMove = false)
                    turn.player.user.id == user.id -> GameEntry(match, with, "Your turn", yourMove = true)
                    else -> GameEntry(match, with, "${turn.player.user.name ?: "Someone"}'s turn", yourMove = false)
                }
            }
        }
    }

    /** Who a match is with, as the list says it. */
    private fun withOf(user: User, match: GameMatch, players: List<GamePlayer>): String = when (match.mode) {
        GameMode.SOLO -> "On your own"
        GameMode.FRIEND -> "With " + (players.firstOrNull { it.user.id != user.id }?.user?.name ?: "a friend")
        GameMode.CLUB -> match.club?.name ?: "Your club"
    }

    /** How a match ended: the visitor's own score on their own, else who won, each score as [score] says it. */
    private fun resultOf(user: User, players: List<GamePlayer>, score: (Int) -> String): String {
        if (players.size == 1) return "Over: ${score(players.single().score)}"
        val best = players.maxOf { it.score }
        val winners = players.filter { it.score == best }
        return when {
            winners.size > 1 -> "A tie, with ${score(best)}"
            winners.single().user.id == user.id -> "You won, with ${score(best)}"
            else -> "${winners.single().user.name ?: "Someone"} won, with ${score(best)}"
        }
    }

    private companion object {
        val RECENT: Duration = Duration.ofDays(21)
        const val LIMIT = 12
        /** The wall's posters: some of the hundred best-known movies of the visitor's taste. */
        const val POSTERS = 30
        const val POSTER_POOL = 100
        const val POSTER_VOTES = 25_000
    }
}
