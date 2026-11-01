package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.PrincipalPk
import st.orm.demo.imdb.model.Principal_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.Ref
import st.orm.repository.EntityRepository
import st.orm.repository.entity
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList
import st.orm.template.neq
import java.math.BigDecimal

/**
 * Query result shape: one movie in a person's filmography — the credit
 * itself (which carries the movie and role) plus the movie's rating for
 * sorting and display. Not backed by a database table or view, so it is a
 * plain data class — deliberately not a Data type.
 */
data class FilmographyEntry(
    val principal: Principal,
    val movie: Movie,
    val averageRating: BigDecimal
)

/**
 * Query result shape: aggregate statistics for a person's filmography,
 * shown on the person detail page. The average is null for persons without
 * rated movies. Not backed by a database table or view, so it is a plain
 * data class — deliberately not a Data type.
 */
data class PersonStatistics(
    val movieCount: Long,
    val averageRating: BigDecimal?
)

/**
 * Query result shape: a movie related to another movie through shared cast
 * members, ranked by how many cast members the two movies have in common.
 * Not backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
data class RelatedMovie(
    val movie: Movie,
    val sharedCastCount: Long
)

/**
 * Query result shape: an actor or actress with the number of movies they
 * appeared in. Not backed by a database table or view, so it is a plain
 * data class — deliberately not a Data type.
 */
@Serializable
data class ProlificActor(
    val person: Person,
    val movieCount: Long
)

/**
 * Query result shape: the number of acting credits per person, keyed by the
 * credit's foreign key, so the count needs no join to the person table.
 */
data class ActingCreditCount(
    val person: Ref<Person>,
    val creditCount: Long
)

interface PrincipalRepository : EntityRepository<Principal, PrincipalPk> {

    /** The cast and crew of a movie in IMDB billing order. */
    fun findCast(movie: Movie) =
        select(Principal_.movie eq movie)
            .orderBy(Principal_.ordering)
            .resultList

    /**
     * A person's filmography sorted by rating, best first. The credit holds
     * the movie as a Ref, so the movie is selected alongside it: the join it
     * needs is the one the rating join already asks for.
     */
    fun findFilmography(person: Person) =
        select<FilmographyEntry, _, _> { "${Principal::class}, ${Movie::class}, ${Rating_.averageRating}" }
            .innerJoin<Rating>().on<Movie>()
            .where(Principal_.person eq person)
            .orderByDescending(Rating_.averageRating)
            .resultList

    /** Movie count and average rating across a person's filmography. */
    fun findStatistics(person: Person) =
        select<PersonStatistics, _, _> { "COUNT(*), AVG(${Rating_.averageRating})" }
            .innerJoin<Rating>().on<Movie>()
            .where(Principal_.person eq person)
            .singleResult

    /**
     * Movies that share cast members with a given movie, ranked by how many
     * cast members they share. The caller passes the cast it already loaded,
     * which turns a self-join into a straightforward aggregation.
     */
    fun findMoviesSharingCast(
        castMembers: List<Person>,
        excludedMovie: Movie,
        limit: Int
    ) =
        select<RelatedMovie, _, _> { "${Movie::class}, COUNT(*)" }
            .where((Principal_.person inList castMembers) and (Principal_.movie neq excludedMovie))
            .groupBy(Principal_.movie)
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList

    /**
     * Most prolific actors for the statistics page: COUNT + ORDER BY. The
     * credits are counted per person by their foreign key, which names the
     * column without joining the person table, so the database counts close
     * to half a million acting credits from a partial index (V1) alone and
     * joins nothing. Only the people who made the list are then loaded, in
     * one query; joining every person before counting took four times as long.
     */
    fun findMostProlificActors(limit: Int): List<ProlificActor> {
        val counts = select<ActingCreditCount, _, _> { "${Principal_.person}, COUNT(*)" }
            .where(Principal_.category inList listOf("actor", "actress"))
            .groupBy(Principal_.person)
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList
        val people = orm.entity<Person>().findAllByRef(counts.map { it.person }).associateBy<Person, Any> { it.id }
        return counts.map { ProlificActor(people.getValue(it.person.id()), it.creditCount) }
    }
}
