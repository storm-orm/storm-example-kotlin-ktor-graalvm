package st.orm.demo.imdb.service

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

/** The rules of every game, in the order of the levels. */
object GameRules {
    private val rules = listOf(
        GameRule(Game.HIGHER_LOWER, "Higher or Lower", Level.EXTRA, PoolKind.UNION),
        GameRule(Game.SWIPE, "Swipe", Level.EXTRA, PoolKind.RECOMMENDED),
        GameRule(Game.CHAIN, "Movie Chain", Level.BIT_PART, PoolKind.UNION),
        GameRule(Game.CONNECT, "Connect", Level.BIT_PART, PoolKind.UNION),
        GameRule(Game.DRAFT, "Movie Draft", Level.SUPPORTING, PoolKind.UNION),
        // Total Recall is one game of two kinds, of a movie (CAST) or of a person (REEL): one rule each, one name.
        GameRule(Game.CAST, "Total Recall", Level.SUPPORTING, PoolKind.INTERSECTION),
        GameRule(Game.REEL, "Total Recall", Level.SUPPORTING, PoolKind.INTERSECTION)
    ).associateBy { it.game }

    fun of(game: Game): GameRule = rules.getValue(game)

    /** The games a level unlocks, in their order, a game of two kinds once. */
    fun unlockedAt(level: Level): List<GameRule> = rules.values.filter { it.level == level }.distinctBy { it.title }

    val all: List<GameRule> get() = rules.values.toList()

    /** Every game once, by name, for the lists that tell about them. */
    val named: List<GameRule> get() = rules.values.distinctBy { it.title }
}
