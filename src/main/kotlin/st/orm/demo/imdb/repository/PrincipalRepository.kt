package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Movie_
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Person_
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.PrincipalPk
import st.orm.demo.imdb.model.Principal_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.repository.EntityRepository
import st.orm.repository.select
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

interface PrincipalRepository : EntityRepository<Principal, PrincipalPk> {

    /** The cast and crew of a movie in IMDB billing order. */
    fun findCast(movie: Movie) =
        select(Principal_.movie eq movie)
            .orderBy(Principal_.ordering)
            .resultList

    /**
     * A person's filmography sorted by rating, best first. Each entry
     * carries the full credit (movie included) plus the rating value from
     * an explicit join to the rating table.
     */
    fun findFilmography(person: Person) =
        select<FilmographyEntry, _, _> { "${Principal::class}, ${Rating_.averageRating}" }
            .innerJoin<Rating>().on<Movie>()
            .where(Principal_.person eq person)
            .orderByDescendingAny(Rating_.averageRating)
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
            .groupByAny(Movie_.id, Movie_.primaryTitle, Movie_.originalTitle, Movie_.startYear, Movie_.runtimeMinutes)
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList

    /** Most prolific actors for the statistics page: COUNT + ORDER BY. */
    fun findMostProlificActors(limit: Int) =
        select<ProlificActor, _, _> { "${Person::class}, COUNT(*)" }
            .where(Principal_.category inList listOf("actor", "actress"))
            .groupByAny(Person_.id, Person_.primaryName, Person_.birthYear, Person_.deathYear)
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList
}
