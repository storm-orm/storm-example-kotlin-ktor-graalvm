package st.orm.demo.imdb.model

import kotlinx.serialization.Serializable
import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.Json
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.math.BigDecimal
import java.time.Instant

/** What a reason for a recommendation is about; the text is what the card shows. */
enum class ReasonKind { SEED, PERSON, GENRE, DECADE, NEW, RATING, TRENDING }

/** One reason a movie was recommended, as the card shows it: "Because you saw Inception". */
@Serializable
data class Reason(val kind: ReasonKind, val text: String)

data class RecommendationPk(
    val userId: String,
    val movieId: String
)

/**
 * A movie recommended to a user: the pick, its score, the movie of the
 * user's own that argued most for it (the seed, for the "Because you saw"
 * shelves; none for a pick that owes nothing to the user's lists), the
 * reasons the card shows, stored as JSON, and how often the pick was shown,
 * which decays its score so the same picks do not greet the user every
 * visit. The rows of a user are replaced as a whole each time they are
 * computed (RecommendationService), the counts carried over for the movies
 * that stay, so the key is the pair of user and movie and the user is a Ref.
 */
data class Recommendation(
    @PK(generation = NONE) val id: RecommendationPk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    @FK @Persist(insertable = false, updatable = false) val movie: Movie,
    /** The ranking score, a decimal: the page scrolls by it, and a cursor carries decimals, not doubles. */
    val score: BigDecimal,
    @FK val seedMovie: Movie?,
    @Json val reasons: List<Reason>,
    val computedAt: Instant,
    val shownCount: Int = 0,
    val lastShownAt: Instant? = null
) : Entity<RecommendationPk> {
    constructor(
        user: User,
        movie: Movie,
        score: BigDecimal,
        seedMovie: Movie?,
        reasons: List<Reason>,
        computedAt: Instant,
        shownCount: Int = 0,
        lastShownAt: Instant? = null
    ) : this(
        id = RecommendationPk(userId = user.id, movieId = movie.id),
        user = user.ref(),
        movie = movie,
        score = score,
        seedMovie = seedMovie,
        reasons = reasons,
        computedAt = computedAt,
        shownCount = shownCount,
        lastShownAt = lastShownAt
    )

    /** The row after being shown once more, now. */
    fun shown(at: Instant): Recommendation = copy(shownCount = shownCount + 1, lastShownAt = at)
}
