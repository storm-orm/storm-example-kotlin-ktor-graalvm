package st.orm.demo.imdb.model

/**
 * How far a user has come as a watcher, named by the billing ranks of a
 * cast: every movie watched counts one, a movie checked in one and a half,
 * and the sum reaches each level's [threshold] in turn. A level reached is
 * kept: unmarking movies later does not take it away. The levels unlock
 * the games, the harder the game, the higher the level.
 */
enum class Level(val threshold: Int, val label: String, val blurb: String) {
    EXTRA(0, "Extra", "Everyone starts here. Watch, check in, and move up the bill."),
    BIT_PART(25, "Bit Part", "A few lines: enough movies watched to link them in a chain."),
    SUPPORTING(100, "Supporting", "A hundred movies: enough to draft from, and to know a cast by heart."),
    LEAD(250, "Lead", "The name above the title: the hard rounds open up."),
    STAR(500, "Star", "Five hundred movies watched: the house takes you seriously."),
    LEGEND(1000, "Legend", "A thousand movies. One step left: your star in the pavement."),
    WALK_OF_FAME(2000, "Walk of Fame", "Two thousand movies: your own star, set in the pavement where the stars are.");

    /** The level after this one, or null at the top. */
    val next: Level? get() = entries.getOrNull(ordinal + 1)

    /** Whether this level is at least [other]. */
    fun reaches(other: Level): Boolean = ordinal >= other.ordinal

    companion object {
        /** The highest level a score reaches. */
        fun of(score: Double): Level = entries.last { score >= it.threshold }
    }
}
