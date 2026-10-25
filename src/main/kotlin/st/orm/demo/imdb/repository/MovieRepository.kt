package st.orm.demo.imdb.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import st.orm.Ref
import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieFilter
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.Movie_
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Principal_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.demo.imdb.model.WikidataSource
import st.orm.demo.imdb.model.WikidataSource_
import st.orm.repository.EntityRepository
import st.orm.repository.select
import st.orm.template.eq
import st.orm.template.entityId
import st.orm.template.eqIgnoreCase
import st.orm.template.greaterEq
import st.orm.template.inList
import st.orm.template.isNotNull
import st.orm.template.lessEq
import st.orm.template.or
import st.orm.template.ref
import st.orm.template.search
import st.orm.template.startsWithIgnoreCase
import st.orm.template.suggest
import java.time.Instant

/**
 * Query result shape: the number of movies released in one decade,
 * e.g. 1990 -> 2412. Not backed by a database table or view, so it is a
 * plain data class — deliberately not a Data type.
 */
@Serializable
data class DecadeMovieCount(
    val decade: Int,
    val movieCount: Long
)

interface MovieRepository : EntityRepository<Movie, String> {

    /**
     * Movies grouped per decade for the statistics page. The decade bucket
     * is a computed expression, so the SELECT and GROUP BY use a template —
     * still with metamodel column references.
     */
    fun countMoviesPerDecade() =
        select<DecadeMovieCount, _, _> { "(${Movie_.startYear} / 10) * 10, COUNT(*)" }
            .where(Movie_.startYear.isNotNull())
            .groupBy { "(${Movie_.startYear} / 10) * 10" }
            .orderBy { "(${Movie_.startYear} / 10) * 10" }
            .resultList

    /**
     * Movie search with keyset scrolling. Every word of the query must occur
     * in the title, the original title or the name of a lead (one of the
     * first three billed) or the director, ignoring case and accents, so
     * "leon" finds Léon, "matrix reloaded" finds The Matrix Reloaded,
     * "fabuleux destin" finds Amélie and "matrix keanu" finds both Matrix
     * movies; a quoted phrase counts as one word, and % or _ match
     * themselves. The titles are matched in the query columns the database
     * stores them in without their accents (V2); each column names the
     * function that computed it, so the query is folded by that function as it
     * is compared, and so are the names ([folded]). The trigram indexes are
     * built on exactly these expressions.
     * The credits are a semi-join: they filter the movies without adding rows,
     * so each movie comes back once without a DISTINCT, each word may match a
     * different credit, and a movie without credits is still found by its
     * title. The condition on the join limits the credits to leads and
     * directors, which the partial index of V1 holds, so a fragment as common
     * as "rob" reads a few thousand index entries instead of every credit of
     * every Robert.
     * The results are ranked as the suggestions are: a title equal to the
     * query first, a title starting with it next, then the rest. Scroll
     * follows the ranks, so the [scrollable] orders each rank, by the vote
     * count of the joined rating on the search page ([mostVotedFirst]). The
     * returned Window offers both navigation modes: typed next()/previous()
     * Scrollables for server-side code, and opaque nextCursor()/previousCursor()
     * strings for clients.
     */
    fun searchMovies(query: String, scrollable: Scrollable<Movie>): Window<Movie> =
        select()
            .innerJoin<Rating>().on<Movie>()
            .semiJoin<Principal>().on<Movie> { leadOrDirector() }
            .where(search(query, Movie_.primaryTitleFolded, Movie_.originalTitleFolded, Principal_.person.primaryName.folded))
            .orderFirst(titleEquals(query))
            .orderFirst(titleStartsWith(query))
            .narrow<Movie>()
            .scroll(scrollable)

    /**
     * Movie suggestions for the search auto-complete, matched and ranked as
     * [searchMovies] matches and ranks them: a title equal to the query
     * first and a title starting with it next, so "napoleon" puts Napoleon
     * above the more popular Napoleon Dynamite, and both above the Leonardo
     * DiCaprio movies "leon" also finds; within each rank the vote count
     * orders them, so well-known movies surface first. The rank is computed
     * over the matched rows only, which the sort reads anyway. The box
     * queries on every keystroke, so it uses suggest rather than search:
     * words of one or two letters, which the trigram indexes cannot serve,
     * are ignored, and nothing is suggested until a word of three letters is
     * typed; "de niro" suggests what "niro" does. The [TITLE_NOISE] words
     * are ignored as well, so "the god" suggests what "god" does, ranked by
     * the whole text, which puts The Godfather first.
     */
    fun findMovieSuggestions(query: String, limit: Int): List<Movie> =
        select()
            .innerJoin<Rating>().on<Movie>()
            .semiJoin<Principal>().on<Movie> { leadOrDirector() }
            .where(suggest(query, TITLE_NOISE, Movie_.primaryTitleFolded, Movie_.originalTitleFolded, Principal_.person.primaryName.folded))
            .orderFirst(titleEquals(query))
            .orderFirst(titleStartsWith(query))
            .orderByDescending(Rating_.voteCount)
            .limit(limit)
            .resultList

    /**
     * The movies in a genre that [filter] lets through, with keyset
     * scrolling. The junction table has a composite key and cannot be
     * scrolled directly, so the scroll runs on
     * the movie's simple primary key with a JOIN through the junction table.
     * The scroll key has to identify one movie, which a key on the junction
     * table would not. The rating is joined so the [scrollable] can order the
     * genre by its vote count, as the browse page does ([mostVotedFirst]);
     * every imported movie has one, so the inner join drops none.
     */
    fun scrollByGenre(genre: Genre, filter: MovieFilter?, scrollable: Scrollable<Movie>): Window<Movie> =
        inGenre(genre, filter)
            .innerJoin<Rating>().on<Movie>()
            .narrow<Movie>()
            .scroll(scrollable)

    /** How many movies of a genre [filter] lets through: the count the browse page shows. */
    fun countByGenre(genre: Genre, filter: MovieFilter?): Long =
        inGenre(genre, filter).resultCount

    /** The movies of a genre that [filter] lets through. */
    private fun inGenre(genre: Genre, filter: MovieFilter?) =
        select {
            innerJoin<MovieGenre, Movie>()
            where(MovieGenre_.genre eq genre)
            keepTo(filter)
        }

    /**
     * Which of these movies [filter] lets through: for the candidates of the
     * recommendations that come from more than one query.
     */
    fun findMatching(movies: Collection<Ref<Movie>>, filter: MovieFilter): Set<Ref<Movie>> =
        if (movies.isEmpty()) emptySet() else select {
            where(Movie_.id inList movies.map { it.entityId() })
            keepTo(filter)
        }.resultList.mapTo(HashSet()) { it.ref() }

    /**
     * The movies Wikidata was not asked about since [askedSince], in windows
     * of [windowSize] over the key: the anti-join leaves out the movies with
     * an answer that recent. Each window is one statement that has closed
     * before the loop sees it, so the loop can call Wikidata and write the
     * answers between windows.
     */
    fun findNotAskedOfWikidataSince(askedSince: Instant, windowSize: Int): Flow<Window<Movie>> =
        select()
            .antiJoin<WikidataSource>().on<Movie> { WikidataSource_.fetchedAt greaterEq askedSince }
            .narrow<Movie>()
            .windows(windowSize)

    companion object {
        /**
         * Most votes first, then the id, which addresses a row: the order of
         * the search page within each rank and of the browse page. The vote
         * count is a column of the rating [searchMovies] and [scrollByGenre]
         * join.
         */
        fun mostVotedFirst(size: Int): Scrollable<Movie> =
            Scrollable.of(Movie_.id, size).sortByDescending(Rating_.voteCount)
    }
}

/**
 * The words the movie suggestions ignore: the articles and conjunctions of the
 * catalogue's main title languages, which occur in a large share of the titles
 * and narrow nothing. A suggestion matches every word as a substring and ranks
 * all the matches, so they cost as much as they say little: on the 99,000 movie
 * catalogue "the" matches 27,000 titles and "and" 27,000 names (Andrew,
 * Anderson). Typed with another word, the other word does the matching, and the
 * ranks still read the whole text.
 */
private val TITLE_NOISE = setOf(
    "the", "and", "for", "with", "from",    // English
    "les", "des", "une",                    // French
    "der", "die", "das", "und",             // German
    "los", "las", "del", "una"              // Spanish, Italian
)

/** The title or the original title equals the text, ignoring case. */
private fun titleEquals(text: String) =
    (Movie_.primaryTitleFolded eqIgnoreCase text) or (Movie_.originalTitleFolded eqIgnoreCase text)

/** The title or the original title starts with the text, ignoring case. */
private fun titleStartsWith(text: String) =
    (Movie_.primaryTitleFolded startsWithIgnoreCase text) or (Movie_.originalTitleFolded startsWithIgnoreCase text)

/**
 * The credits a movie is found by: the leads, billed first to third, and the
 * directors. The partial index of migration V1 holds exactly these rows.
 */
private fun leadOrDirector() =
    (Principal_.ordering lessEq 3) or (Principal_.category eq "director")
