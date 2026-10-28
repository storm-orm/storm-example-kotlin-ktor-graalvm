package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK

/**
 * The theme of a Movie Draft: a genre and a decade, every pick a movie of
 * both. A dependent one-to-one: the key is the match itself.
 */
data class DraftTheme(
    @PK(generation = NONE) @FK val match: GameMatch,
    @FK val genre: Genre,
    /** The decade's first year: 1990 for the nineties. */
    val decade: Int
) : Entity<GameMatch> {
    /** The theme as the page names it: "Sci-Fi of the 1990s". */
    val label: String get() = "${genre.name} of the ${decade}s"
}
