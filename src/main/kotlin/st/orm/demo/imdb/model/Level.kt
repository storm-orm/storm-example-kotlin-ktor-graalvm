package st.orm.demo.imdb.model

/**
 * Meryl Streep, the greatest of all time by common consent and by the
 * count: twenty-one Oscar nominations, more than anyone. The top level is
 * named for what she is called.
 */
const val MERYL_STREEP = "nm0000658"

/**
 * What the top four levels bring home, worn on the avatar wherever it
 * shows: a star in the pavement, a Golden Globe, an Oscar, and the goat
 * itself. [mark] names the drawing (the honours sprite in
 * fragments/layout.html), [title] what it is.
 */
enum class Honour(val mark: String, val title: String) {
    STAR("star", "A star on the Walk of Fame"),
    GLOBE("globe", "A Golden Globe"),
    OSCAR("oscar", "An Oscar"),
    GOAT("goat", "The greatest of all time")
}

/**
 * How far a user has come as a watcher, named by the billing ranks of a
 * cast: every movie watched counts one, a movie checked in one and a half,
 * and the sum reaches each level's [threshold] in turn. A level reached is
 * kept: unmarking movies later does not take it away. The levels unlock
 * the games, the harder the game, the higher the level; the top four
 * come with an [honour].
 */
enum class Level(val threshold: Int, val label: String, val blurb: String, val honour: Honour? = null) {
    EXTRA(0, "Extra", "Everyone starts here. Watch, check in, and move up the bill."),
    BIT_PART(25, "Bit Part", "A few lines: enough movies watched to link them in a chain."),
    SUPPORTING(100, "Supporting", "A hundred movies: enough to draft from, and to know a cast by heart."),
    LEAD(250, "Lead", "The name above the title: the hard rounds open up."),
    STAR(500, "Star", "Five hundred movies: your own star, set in the pavement where the stars are.", Honour.STAR),
    SUPER_STAR(1000, "Super Star", "A thousand movies: a Golden Globe, the one the press hands out in January, when the whole town is watching for the Oscars.", Honour.GLOBE),
    LEGEND(1500, "Legend", "Fifteen hundred movies: an Oscar, from your peers, the one that changes a career.", Honour.OSCAR),
    GOAT(2500, "GOAT", "Twenty-five hundred movies: the greatest of all time, as the town calls Meryl Streep, twenty-one nominations in.", Honour.GOAT);

    /** The level after this one, or null at the top. */
    val next: Level? get() = entries.getOrNull(ordinal + 1)

    /** Whether this level is at least [other]. */
    fun reaches(other: Level): Boolean = ordinal >= other.ordinal

    companion object {
        /** The highest level a score reaches. */
        fun of(score: Double): Level = entries.last { score >= it.threshold }
    }
}
