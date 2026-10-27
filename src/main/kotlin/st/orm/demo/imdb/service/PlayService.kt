package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.GameMatch
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.GamePlayer
import st.orm.demo.imdb.model.GamePlayerPk
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.template.transaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One of the visitor's games on the Play page: the match, who it is with,
 * how it stands, whether it waits on them ([yourMove]), whether it is
 * [over], and whether they play it ([mine]), which a club's round they have
 * not joined yet they do not.
 */
data class GameEntry(val match: GameMatch, val with: String, val status: String, val yourMove: Boolean, val over: Boolean = false, val mine: Boolean = true) {
    val title: String get() = when (match.game) {
        Game.CHAIN -> "Movie Chain"
        Game.DRAFT -> "Movie Draft"
        Game.HIGHER_LOWER -> "Higher or Lower"
        Game.SWIPE -> "Swipe Round"
        Game.CONNECT -> "Connect"
        Game.CAST -> "Role Call"
        Game.REEL -> "Reel Call"
    }

    /** The game's emoji, for the lists. */
    val icon: String get() = when (match.game) {
        Game.CHAIN -> "🎞️"
        Game.DRAFT -> "🎟️"
        Game.HIGHER_LOWER -> "🎬"
        Game.SWIPE -> "🍿"
        Game.CONNECT -> "📽️"
        Game.CAST -> "🎭"
        Game.REEL -> "🎥"
    }
}

/**
 * The Play page: the visitor's open games, the ones waiting on them first,
 * the few that closed last, who they can start one with, and the posters of
 * the movies of their taste, for the wall across the top.
 */
data class PlayView(val games: List<GameEntry>, val finished: List<GameEntry>, val company: Company, val yesterday: YesterdayQuiz?)

/** A page of the visitor's archive: their closed games, the last closed first. */
data class ArchiveView(val games: List<GameEntry>, val page: Int, val hasNext: Boolean)

/**
 * The Play page's list of the visitor's games: the open matches they play,
 * and the rounds of their clubs they have not played yet, each saying how
 * it stands; the ones waiting on them first. A game that is over leaves the
 * list for the archive the first time the list finds it so, and a player
 * may close an open game: on their own or with a friend that ends it, in a
 * club they leave it to the others. No more than [MAX_OPEN] games one
 * started are open at once: a new one closes the oldest played on one's
 * own, and waits when all are played with others.
 */
class PlayService(
    private val matchService: MatchService,
    private val gameMatchRepository: GameMatchRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val chainService: ChainService,
    private val draftService: DraftService,
    private val connectService: ConnectService,
    private val ratingRepository: RatingRepository,
    private val quizService: QuizService,
    private val castService: CastService
) {

    suspend fun playOf(user: User, now: Instant = Instant.now()): PlayView = transaction {
        val company = matchService.companyOf(user)
        val open = openGamesOf(user, now)
        // The club's rounds the visitor has no part in yet; one they closed is not offered again.
        val rounds = gameMatchRepository.findOpen(company.clubs, now, LIMIT)
        val joined = gamePlayerRepository.findMine(user, rounds).map { it.match.id }.toSet()
        val entries = open + rounds.filter { it.id !in joined }.map { entryOf(user, it, null, now) }
        PlayView(
            entries.sortedWith(compareByDescending<GameEntry> { it.yourMove }.thenByDescending { it.match.createdAt }),
            gamePlayerRepository.findClosed(user, 0, FINISHED_SHOWN).content().map { closedEntryOf(user, it, now) },
            company,
            quizService.yesterdayOf(user, LocalDate.ofInstant(now, ZoneOffset.UTC))
        )
    }

    /**
     * Closes a game for the visitor: on their own or with a friend the game
     * ends as it stands, for everyone; in a club they leave it, and the
     * others play on, passing the visitor's turns by as they run out.
     */
    suspend fun close(user: User, matchId: Long, now: Instant = Instant.now()) = transaction {
        gamePlayerRepository.findById(GamePlayerPk(matchId = matchId, userId = user.id))
            ?.takeIf { it.closedAt == null }
            ?.let { close(it, now) }
    }

    /**
     * Makes room for a game the visitor starts: with [MAX_OPEN] games they
     * started open, the oldest of those they play on their own closes. False
     * when there is no room, all of them being played with others.
     */
    suspend fun makeRoom(user: User, now: Instant = Instant.now()): Boolean = transaction {
        val started = openGamesOf(user, now).filter { it.match.host.id == user.id }
        if (started.size < MAX_OPEN) return@transaction true
        val oldest = started.filter { it.match.mode == GameMode.SOLO }.minByOrNull { it.match.createdAt } ?: return@transaction false
        gamePlayerRepository.findById(GamePlayerPk(matchId = oldest.match.id, userId = user.id))?.let { close(it, now) }
        true
    }

    /** A page of the visitor's archive, 0 the first. */
    suspend fun archiveOf(user: User, page: Int, now: Instant = Instant.now()): ArchiveView = transaction(readOnly = true) {
        val closed = gamePlayerRepository.findClosed(user, page, ARCHIVE_PAGE)
        ArchiveView(closed.content().map { closedEntryOf(user, it, now) }, page, closed.hasNext())
    }

    /**
     * The visitor's open games as they stand at [now]; the ones found over
     * close, to the archive. A match nobody moved in for [IDLE] ends here,
     * whatever its game: the list never grows with games left behind.
     */
    private fun openGamesOf(user: User, now: Instant): List<GameEntry> =
        gamePlayerRepository.findOpen(user).mapNotNull { me ->
            val players = gamePlayerRepository.findPlayers(me.match)
            val match = endIfIdle(me.match, players, now)
            val entry = entryOf(user, match, me, now, players)
            if (entry.over) {
                gamePlayerRepository.update(me.copy(closedAt = now, closedStatus = entry.status, closedWith = entry.with))
                null
            } else entry
        }

    /** Ends a match whose last move, or start, is more than [IDLE] ago; as it is otherwise. */
    private fun endIfIdle(match: GameMatch, players: List<GamePlayer>, now: Instant): GameMatch {
        if (match.endedAt != null) return match
        val lastMove = (players.map { it.updatedAt } + match.createdAt).max()
        if (Duration.between(lastMove, now) <= IDLE) return match
        return match.copy(endedAt = now).also(gameMatchRepository::update)
    }

    /** Closes a game for a player, keeping how it stood: ended as it is, or, in a club, left to the others. */
    private fun close(me: GamePlayer, now: Instant) {
        val match = if (me.match.mode != GameMode.CLUB && me.match.endedAt == null) me.match.copy(endedAt = now).also(gameMatchRepository::update) else me.match
        val entry = entryOf(me.user, match, me, now)
        val status = if (entry.over) entry.status else "You left this game"
        gamePlayerRepository.update(me.copy(closedAt = now, closedStatus = status, closedWith = entry.with))
    }

    /** How a closed game stood when it closed, as kept then; worked out for one closed before that was kept. */
    private fun closedEntryOf(user: User, me: GamePlayer, now: Instant): GameEntry =
        me.closedStatus?.let { GameEntry(me.match, me.closedWith ?: "", it, yourMove = false, over = true) }
            ?: entryOf(user, me.match, me, now).let { if (it.over) it else it.copy(status = "You left this game", yourMove = false, over = true) }

    /**
     * What a match looks like at [now], as a short text that changes
     * whenever another player moves, or a turn passes on by time: a page
     * that waits on the others asks for it now and then, and reloads when it
     * changed. Null for a match the visitor may not look at.
     */
    suspend fun versionOf(user: User, matchId: Long, now: Instant = Instant.now()): String? = transaction(readOnly = true) {
        val match = matchService.visibleMatch(user, matchId) ?: return@transaction null
        val players = gamePlayerRepository.findPlayers(match)
        val standing = players.joinToString(",") { "${it.user.id}:${it.score}:${it.finished}:${it.updatedAt.toEpochMilli()}" }
        val game = when (match.game) {
            Game.CHAIN -> chainService.versionOf(match, players, now)
            Game.DRAFT -> draftService.versionOf(match, now)
            Game.CONNECT -> connectService.versionOf(match)
            Game.CAST, Game.REEL -> castService.versionOf(match, now)
            Game.HIGHER_LOWER, Game.SWIPE -> ""
        }
        "${match.endedAt}|${match.isOver(now)}|$standing|$game"
    }

    /**
     * A club's games of the last weeks, for its page, each as it stands for
     * the visitor, a member, who may not have played it yet.
     */
    suspend fun clubGamesOf(user: User, club: Club, now: Instant = Instant.now()): List<GameEntry> = transaction(readOnly = true) {
        gameMatchRepository.findRecentOf(club, now.minus(RECENT), LIMIT).map { match ->
            entryOf(user, match, gamePlayerRepository.findById(GamePlayerPk(matchId = match.id, userId = user.id)), now)
        }
    }

    /** How a match stands for the visitor: [me] is their player, or null for a club's round they have not played. */
    private fun entryOf(user: User, match: GameMatch, me: GamePlayer?, now: Instant, players: List<GamePlayer> = gamePlayerRepository.findPlayers(match)): GameEntry {
        val with = withOf(user, match, players)
        val entry = when (match.game) {
            Game.HIGHER_LOWER -> when {
                me == null -> if (match.isOver(now)) GameEntry(match, with, resultOf(user, players) { "a streak of $it" }, yourMove = false, over = true)
                    else GameEntry(match, with, "Your turn: the round is open", yourMove = true)
                !me.finished && !match.isOver(now) -> GameEntry(match, with, if (me.score == 0) "Your turn" else "Your turn: a streak of ${me.score} going", yourMove = true)
                match.isOver(now) || match.mode == GameMode.SOLO -> GameEntry(match, with, resultOf(user, players) { "a streak of $it" }, yourMove = false, over = true)
                else -> GameEntry(match, with, "You scored ${me.score}; waiting for the others", yourMove = false)
            }
            Game.CONNECT -> {
                // Played together, no turns: an open puzzle is always one the visitor can add to.
                val (over, standing) = connectService.standingOf(match)
                GameEntry(match, with, standing, yourMove = !over, over = over)
            }
            Game.SWIPE -> when {
                match.isOver(now) -> GameEntry(match, with, "The round is closed: see what everyone wants", yourMove = false, over = true)
                me == null || !me.finished -> GameEntry(match, with, "Your turn: swipe the posters", yourMove = true)
                else -> GameEntry(match, with, "You swiped them all; waiting for the others", yourMove = false)
            }
            Game.DRAFT -> {
                val turn = draftService.turnIn(match, now)
                when {
                    turn == null -> GameEntry(match, with, "The draft is in: see who won", yourMove = false, over = true)
                    turn.isUser(user) -> GameEntry(match, with, "Your pick", yourMove = true)
                    else -> GameEntry(match, with, "${turn.name}'s pick", yourMove = false)
                }
            }
            Game.CAST, Game.REEL -> castService.standingOf(user, match, now).let { (over, standing, yourMove) ->
                GameEntry(match, with, standing, yourMove = yourMove, over = over)
            }
            Game.CHAIN -> {
                val turn = chainService.turnIn(match, players, now)
                when {
                    turn == null -> GameEntry(match, with, resultOf(user, players) { if (it == 1) "1 point" else "$it points" }, yourMove = false, over = true)
                    turn.player.user.id == user.id -> GameEntry(match, with, "Your turn", yourMove = true)
                    else -> GameEntry(match, with, "${turn.player.user.name ?: "Someone"}'s turn", yourMove = false)
                }
            }
        }
        return entry.copy(mine = me != null)
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

    companion object {
        /** The games one started that may be open at once. */
        const val MAX_OPEN = 8
        private val RECENT: Duration = Duration.ofDays(21)
        private const val LIMIT = 12
        /** A match nobody moved in for this long ends by itself. */
        val IDLE: Duration = Duration.ofDays(7)
        /** The games that closed last, under the open ones. */
        private const val FINISHED_SHOWN = 3
        private const val ARCHIVE_PAGE = 20
    }
}
