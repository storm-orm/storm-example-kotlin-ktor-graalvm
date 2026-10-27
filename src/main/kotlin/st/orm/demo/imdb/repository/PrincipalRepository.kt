package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.GenerateMetamodel
import st.orm.demo.imdb.model.CheckIn
import st.orm.demo.imdb.model.CheckIn_
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.MovieSeen_
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.PrincipalPk
import st.orm.demo.imdb.model.Principal_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.demo.imdb.model.User
import st.orm.Ref
import st.orm.repository.EntityRepository
import st.orm.repository.entity
import st.orm.repository.select
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList
import st.orm.template.inRefs
import st.orm.template.greaterEq
import st.orm.template.less
import st.orm.template.lessEq
import st.orm.template.neq
import st.orm.template.or
import st.orm.template.ref
import java.math.BigDecimal
import java.time.Instant

/** The credit categories that count as acting. */
val ACTING_CATEGORIES = listOf("actor", "actress")

/** The credit category of a director, as the IMDB dataset names it. */
const val DIRECTOR = "director"

/**
 * Query result shape: one movie in a person's filmography — the credit
 * itself (which carries the movie and role) plus the movie's rating for
 * sorting and display. Not backed by a database table or view, so it is a
 * plain data class — deliberately not a Data type.
 */
@GenerateMetamodel
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
@GenerateMetamodel
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
@GenerateMetamodel
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
 * Query result shape: the number of credits per person, keyed by the
 * credit's foreign key, so the count needs no join to the person table.
 */
@GenerateMetamodel
data class PersonCreditCount(
    val person: Ref<Person>,
    val creditCount: Long
)

/**
 * Query result shape: how many of the movies a user has seen a person is
 * credited on, in the categories asked for. Not backed by a database table
 * or view, so it is a plain data class — deliberately not a Data type.
 */
data class PersonSeenCount(
    val person: Person,
    val movieCount: Long
)

/**
 * Query result shape: the number of credits per person and category, keyed
 * by the credit's foreign key. Not backed by a database table or view, so
 * it is a plain data class — deliberately not a Data type.
 */
@GenerateMetamodel
data class CategoryCreditCount(
    val person: Ref<Person>,
    val category: String,
    val creditCount: Long
)

/**
 * Query result shape: a credit as the pair it links, a person and a movie,
 * both by their foreign keys, so neither table is joined: the edges of the
 * graph of who played with whom. Not backed by a database table or view, so
 * it is a plain data class — deliberately not a Data type.
 */
@GenerateMetamodel
data class CreditLink(
    val person: Ref<Person>,
    val movie: Ref<Movie>
)

/** The favourite actors and directors of a user, for the insights page. */
data class SeenFavourites(
    val actors: List<PersonSeenCount>,
    val directors: List<PersonSeenCount>
)

/**
 * Query result shape: a credit of one of several people, with the movie's
 * rating, which carries the movie: the filmographies the recommendations
 * draw candidates from. Not backed by a database table or view, so it is a
 * plain data class — deliberately not a Data type.
 */
@GenerateMetamodel
data class CandidateCredit(
    val person: Person,
    val category: String,
    val rating: Rating
)

interface PrincipalRepository : EntityRepository<Principal, PrincipalPk> {

    /** The cast and crew of several movies at once, in no particular order. */
    fun findByMovies(movies: List<Ref<Movie>>) =
        findAll(Principal_.movie inRefs movies)

    /**
     * The credits a visitor's taste is read from, for several movies at once:
     * the top-billed cast, by the credit's own billing order, and the
     * director whatever the billing, since IMDB lists directors after the
     * cast.
     */
    fun findTasteCredits(movies: List<Ref<Movie>>, maxOrdering: Int) =
        findAll((Principal_.movie inRefs movies) and ((Principal_.ordering lessEq maxOrdering) or (Principal_.category eq DIRECTOR)))

    /**
     * The filmographies of several people at once, each credit with the
     * movie's rating: the candidates a visitor's favourite people bring in.
     * The person comes with the credit, since the reason on the card names
     * them, and the rating brings the movie.
     */
    fun findFilmographies(people: List<Ref<Person>>) =
        select<CandidateCredit, _, _> { "${Person::class}, ${Principal_.category}, ${Rating::class}" }
            .innerJoin<Rating>().on<Movie>()
            .where(Principal_.person inRefs people)
            .resultList

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

    /** The number of people with at least one acting credit. */
    fun countActors(): Long =
        select<Long, _, _> { "COUNT(DISTINCT ${Principal_.person})" }
            .where(Principal_.category inList ACTING_CATEGORIES)
            .singleResult

    /**
     * Most prolific actors for the statistics page: COUNT + ORDER BY. The
     * credits are counted per person by their foreign key, which names the
     * column without joining the person table, so the database counts close
     * to half a million acting credits from a partial index (V1) alone and
     * joins nothing. Only the people who made the list are then loaded, in
     * one query; joining every person before counting took four times as long.
     */
    fun findMostProlificActors(limit: Int): List<ProlificActor> {
        val counts = select<PersonCreditCount, _, _> { "${Principal_.person}, COUNT(*)" }
            .where(Principal_.category inList ACTING_CATEGORIES)
            .groupBy(Principal_.person)
            .orderByDescending { "COUNT(*)" }
            .limit(limit)
            .resultList
        val people = orm.entity(Person::class).findAllByRef(counts.map { it.person }).associateBy<Person, Any> { it.id }
        return counts.map { ProlificActor(people.getValue(it.person.id()), it.creditCount) }
    }

    /**
     * The actors and the directors the user has seen most often, for the
     * insights page, from one count: the credits join the seen list on the
     * movie and are counted per person and category, by the person's foreign
     * key, so the person table is not joined for each of the thousands of
     * credits of a long seen list. Only a person on at least [minimumMovies]
     * of the user's movies qualifies (HAVING), since a single credit says
     * nothing about a favourite. The people who made either list are then
     * loaded in one query, as for the most prolific actors.
     */
    fun findSeenFavourites(user: User, minimumMovies: Int, limit: Int): SeenFavourites =
        favourites(
            select<CategoryCreditCount, _, _> { "${Principal_.person}, ${Principal_.category}, COUNT(*)" }
                .innerJoin<MovieSeen>().on<Movie>()
                .where((MovieSeen_.user eq user) and (Principal_.category inList ACTING_CATEGORIES + DIRECTOR))
                .groupBy(Principal_.person, Principal_.category)
                .having { "COUNT(*) >= $minimumMovies" }
                .orderByDescending { "COUNT(*)" }
                .resultList,
            limit
        )

    /**
     * The actors and the directors of the movies the user checked in from
     * [from] up to [until]: a year's favourites. The same count as
     * [findSeenFavourites], joined through the check-ins instead, where a
     * movie watched twice is one movie, so the count is of distinct movies.
     */
    fun findCheckedInFavourites(user: User, from: Instant, until: Instant, minimumMovies: Int, limit: Int): SeenFavourites =
        favourites(
            select<CategoryCreditCount, _, _> { "${Principal_.person}, ${Principal_.category}, COUNT(DISTINCT ${CheckIn_.movie})" }
                .innerJoin<CheckIn>().on<Movie>()
                .where((CheckIn_.user eq user) and (CheckIn_.watchedAt greaterEq from) and (CheckIn_.watchedAt less until)
                    and (Principal_.category inList ACTING_CATEGORIES + DIRECTOR))
                .groupBy(Principal_.person, Principal_.category)
                .having { "COUNT(DISTINCT ${CheckIn_.movie}) >= $minimumMovies" }
                .orderByDescending { "COUNT(DISTINCT ${CheckIn_.movie})" }
                .resultList,
            limit
        )

    /** The counts split into the actors and the directors, the people who made either list loaded in one query. */
    private fun favourites(counts: List<CategoryCreditCount>, limit: Int): SeenFavourites {
        val actors = counts.filter { it.category in ACTING_CATEGORIES }.take(limit)
        val directors = counts.filter { it.category == DIRECTOR }.take(limit)
        val people = orm.entity(Person::class).findAllByRef((actors + directors).map { it.person }.distinct()).associateBy { it.ref() }
        fun named(counts: List<CategoryCreditCount>) = counts.map { PersonSeenCount(people.getValue(it.person), it.creditCount) }
        return SeenFavourites(actors = named(actors), directors = named(directors))
    }


    /**
     * The acting credits of several people in the movies at least
     * [minimumVotes] voted on: from people to the movies they played in, one
     * step of the search for how two actors connect. The rating joins on the
     * movie for its votes; the pairs are foreign keys, so neither the person
     * nor the movie is read.
     */
    fun findActingLinks(people: Collection<Ref<Person>>, minimumVotes: Int): List<CreditLink> =
        select<CreditLink, _, _> { "${Principal_.person}, ${Principal_.movie}" }
            .innerJoin<Rating>().on<Movie>()
            .where((Principal_.person inRefs people) and (Principal_.category inList ACTING_CATEGORIES) and (Rating_.voteCount greaterEq minimumVotes))
            .resultList

    /** The cast of several movies, as pairs: from the movies to who played in them, the other step of the search. */
    fun findCastLinks(movies: Collection<Ref<Movie>>): List<CreditLink> =
        select<CreditLink, _, _> { "${Principal_.person}, ${Principal_.movie}" }
            .where((Principal_.movie inRefs movies) and (Principal_.category inList ACTING_CATEGORIES))
            .resultList
}
