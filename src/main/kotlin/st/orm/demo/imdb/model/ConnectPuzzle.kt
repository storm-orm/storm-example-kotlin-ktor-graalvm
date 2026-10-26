package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.Json
import st.orm.PK

/** A way from one movie to another: the movies in turn, from the start to the target, and the actor each step goes through. */
@Serializable
data class ConnectPath(val movies: List<String>, val actors: List<String>)

/**
 * A Connect puzzle: the movie to start from and the one to reach, and par,
 * the fewest links between them, with a way that takes that many, shown
 * when the players give up. The team's hints and undos count against its
 * result. A dependent one-to-one: the key is the match itself; the chain
 * the players build is its [ChainLink]s, the start first.
 */
data class ConnectPuzzle(
    @PK(generation = NONE) @FK val match: GameMatch,
    @FK val start: Movie,
    @FK val target: Movie,
    val par: Int,
    @Json val path: ConnectPath,
    val hints: Int = 0,
    val undos: Int = 0,
    /** The link the hints are for, and how many of its actors they showed. */
    val hintAt: Int? = null,
    val hintShown: Int = 0
) : Entity<GameMatch>
