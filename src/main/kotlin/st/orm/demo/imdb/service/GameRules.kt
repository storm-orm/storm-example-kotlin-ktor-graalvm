package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.DailyGame
import st.orm.demo.imdb.model.Game
import st.orm.demo.imdb.model.Level

/**
 * Where a game's movies come from, once its players are known: the ones
 * they have all watched, the ones any of them has, the ones none of them
 * has yet, or the ones the site would recommend them.
 */
enum class PoolKind(val label: String, val blurb: String) {
    INTERSECTION("Movies you have all watched", "A fair contest: nobody is asked about a movie they have not seen."),
    UNION("Movies any of you has watched", "What the players know between them; the one who watched more knows more of the pool."),
    UNSEEN("Movies none of you has watched", "New to everyone: for finding what to watch next."),
    RECOMMENDED("Movies we would recommend you", "Picked for the players' taste, from what they have not watched yet.")
}

/**
 * What a game asks of its players: the level it unlocks at, and where its
 * movies come from. A game played from what everyone has watched needs
 * every player at its level; one played from what anyone has watched lets
 * a player below it join, at a disadvantage, as the pool is mostly the
 * others' movies.
 */
data class GameRule(val game: Game, val title: String, val level: Level, val pool: PoolKind) {
    /** Whether every player needs the level, rather than the one who starts it. */
    val everyoneNeedsLevel: Boolean get() = pool == PoolKind.INTERSECTION
}

/** What a daily asks of its player: the level it unlocks at. Its movies are the best known of their taste. */
data class DailyRule(val daily: DailyGame, val title: String, val level: Level)

/**
 * The rules of every game, in the order of the levels. A level's rites are
 * earned in games unlocked below it, so nobody is ever held at a level by a
 * game they cannot play yet (LevelServiceTest proves it).
 */
object GameRules {
    private val dailies = listOf(
        DailyRule(DailyGame.ODD_ONE_OUT, "The Outsiders", Level.EXTRA),
        DailyRule(DailyGame.FACE_OFF, "Face/Off", Level.EXTRA),
        DailyRule(DailyGame.CONNECTIONS, "The Breakfast Club", Level.BIT_PART),
        DailyRule(DailyGame.USUAL_SUSPECTS, "The Usual Suspects", Level.SUPPORTING),
        DailyRule(DailyGame.MEMENTO, "Memento", Level.LEAD),
        DailyRule(DailyGame.THE_MASK, "The Mask", Level.LEAD),
        DailyRule(DailyGame.AMADEUS, "Amadeus", Level.STAR)
    ).associateBy { it.daily }

    private val rules = listOf(
        GameRule(Game.HIGHER_LOWER, "Top Gun", Level.EXTRA, PoolKind.UNION),
        GameRule(Game.SWIPE, "Love at First Sight", Level.EXTRA, PoolKind.RECOMMENDED),
        GameRule(Game.CHAIN, "Chain Reaction", Level.BIT_PART, PoolKind.UNION),
        GameRule(Game.CONNECT, "Six Degrees of Separation", Level.BIT_PART, PoolKind.UNION),
        GameRule(Game.DRAFT, "The Wolf of Wall Street", Level.SUPPORTING, PoolKind.UNION),
        // Total Recall is one game of two kinds, of a movie (CAST) or of a person (REEL): one rule each, one name.
        GameRule(Game.CAST, "Total Recall", Level.SUPPORTING, PoolKind.INTERSECTION),
        GameRule(Game.REEL, "Total Recall", Level.SUPPORTING, PoolKind.INTERSECTION)
    ).associateBy { it.game }

    fun of(game: Game): GameRule = rules.getValue(game)

    fun of(daily: DailyGame): DailyRule = dailies.getValue(daily)

    /** The games a level unlocks, by name, in their order, a game of two kinds once: the matches, then the dailies. */
    fun unlockedAt(level: Level): List<String> =
        (rules.values.filter { it.level == level }.map { it.title } + dailies.values.filter { it.level == level }.map { it.title }).distinct()

    /** Every game's level by the game's name, match or daily, for the cards that say it. */
    val levelOf: Map<String, Level> get() = rules.values.associate { it.game.name to it.level } + dailies.values.associate { it.daily.name to it.level }

    val all: List<GameRule> get() = rules.values.toList()

    /** Every game once, by name, for the lists that tell about them. */
    val named: List<GameRule> get() = rules.values.distinctBy { it.title }
}
