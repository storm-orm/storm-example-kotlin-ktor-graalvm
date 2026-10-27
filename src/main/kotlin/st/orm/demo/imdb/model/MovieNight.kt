package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.PK
import st.orm.Ref
import java.time.Instant
import java.time.LocalDate

/**
 * A club's movie night: on a day, hosted by the member who planned it, who
 * chose where it is watched: on some of the members' streaming services
 * ([MovieNightProvider]), in the [country] they play in, at the [cinema],
 * or both; and from what: the members' watchlists and picks, or a [list].
 * The candidates were picked when it was planned ([MovieNightCandidate]);
 * the members vote ([MovieNightVote]), and the [winner] is the movie of
 * the night once the host picks it.
 */
/** How a night's movie is chosen: by a vote on the candidates, or by a swipe round of the club on them. */
enum class NightMethod { VOTE, SWIPE }

data class MovieNight(
    @PK val id: Long = 0,
    @FK val club: Club,
    @FK val host: User,
    val title: String,
    val nightOn: LocalDate,
    /** The country the services stream in and the cinemas play in (ISO 3166-1 alpha-2). */
    val country: String?,
    val cinema: Boolean,
    @FK val list: Ref<MovieList>?,
    @FK val winner: Movie?,
    val createdAt: Instant,
    val method: NightMethod = NightMethod.VOTE,
    /** The club's swipe round on the candidates, for a night chosen by swiping. */
    @FK val swipeMatch: Ref<GameMatch>? = null
) : Entity<Long>
