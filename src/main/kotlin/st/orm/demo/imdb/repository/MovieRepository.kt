package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.Movie_
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Principal_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.repository.EntityRepository
import st.orm.template.eq
import st.orm.template.eqIgnoreCase
import st.orm.template.isNotNull
import st.orm.template.lessEq
import st.orm.template.or
import st.orm.template.search
import st.orm.template.startsWithIgnoreCase
import st.orm.template.suggest

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
     * Movie search with keyset scrolling. Every word of the query must occur
     * in the title, the original title or the name of a lead (one of the
     * first three billed) or the director, ignoring case and accents, so
     * "leon" finds Léon, "matrix reloaded" finds The Matrix Reloaded,
     * "fabuleux destin" finds Amélie and "matrix keanu" finds both Matrix
     * movies; a quoted phrase counts as one word, and % or _ match
     * themselves. The database removes the accents from the titles, the names
     * and the query alike ([folded]), through the expression its trigram
     * indexes are built on.
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
    fun searchMovies(query: String, scrollable: Scrollable<Movie>): Window<Movie> {
        val title = searchText(query)
        return select()
            .innerJoin<Rating>().on<Movie>()
            .semiJoin<Principal>().on<Movie> { leadOrDirector() }
            .where(search(title, Movie_.primaryTitle.folded, Movie_.originalTitle.folded, Principal_.person.primaryName.folded))
            .orderFirst(titleEquals(title))
            .orderFirst(titleStartsWith(title))
            .narrow<Movie>()
            .scroll(scrollable)
    }

    /**
     * Movie suggestions for the search auto-complete, matched and ranked as
     * [searchMovies] matches and ranks them: a title equal to the query
     * first and a title starting with it next, so "napoleon" puts Napoleon
     * above the more popular Napoleon Dynamite, and both above the Leonardo
     * DiCaprio movies "leon" also finds; within each rank the vote count
     * orders them, so well-known movies surface first. The rank is computed
     * over the matched rows only, which the sort reads anyway. The box
     * queries on every keystroke, so it uses suggest rather than search:
     * every statement it renders is served by the trigram indexes of V1. A
     * word of three letters or more matches anywhere and a word of two at
     * the start of a word, so "up" suggests Up and in "de niro" "niro"
     * matches anywhere and "de" at the start of a word. A word of one
     * letter takes no part, and neither do the [TITLE_NOISE] words, so
     * "the god" suggests what "god" does, ranked by the whole query, which
     * puts The Godfather first. An input with no word left, such as "a" or
     * "the", suggests nothing.
     */
    fun findMovieSuggestions(query: String, limit: Int): List<Movie> {
        val title = searchText(query)
        return select()
            .innerJoin<Rating>().on<Movie>()
            .semiJoin<Principal>().on<Movie> { leadOrDirector() }
            .where(suggest(title, TITLE_NOISE, Movie_.primaryTitle.folded, Movie_.originalTitle.folded, Principal_.person.primaryName.folded))
            .orderFirst(titleEquals(title))
            .orderFirst(titleStartsWith(title))
            .orderByDescending(Rating_.voteCount)
            .limit(limit)
            .resultList
    }

    /**
     * All movies in a genre with keyset scrolling. The junction table has a
     * composite key and cannot be scrolled directly, so the scroll runs on
     * the movie's simple primary key, and the junction table is a semi-join:
     * it keeps the movies of the genre without adding rows. The rating is
     * joined so the [scrollable] can order the genre by its vote count, as
     * the browse page does ([mostVotedFirst]); every imported movie has one,
     * so the inner join drops none.
     */
    fun scrollByGenre(genre: Genre, scrollable: Scrollable<Movie>) =
        select()
            .semiJoin<MovieGenre>().on<Movie>()
            .innerJoin<Rating>().on<Movie>()
            .where(MovieGenre_.genre eq genre)
            .narrow<Movie>()
            .scroll(scrollable)

    /**
     * Movies grouped per decade for the statistics page. The decade bucket
     * is a computed expression, so the SELECT and GROUP BY use a template —
     * still with metamodel column references.
     */
    fun countMoviesPerDecade() =
        select(DecadeMovieCount::class) { "(${Movie_.startYear} / 10) * 10, COUNT(*)" }
            .where(Movie_.startYear.isNotNull())
            .groupBy { "(${Movie_.startYear} / 10) * 10" }
            .orderBy { "(${Movie_.startYear} / 10) * 10" }
            .resultList

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
 * The words the movie suggestions ignore: the articles and conjunctions most
 * frequent in titles, in English and the catalogue's other main title
 * languages. The trigram index serves them, but they match a large share of
 * the rows and narrow nothing: typed alone they suggest a page of noise, and
 * next to another word that word does the matching. The ranks still compare
 * the whole query, so "the god" puts The Godfather first.
 */
private val TITLE_NOISE = setOf(
    "the", "and", "for", "with", "from",    // English
    "les", "des", "une",                    // French
    "der", "das", "und", "ein",             // German
    "los", "las", "del", "una",             // Spanish, Italian
    "het", "een"                            // Dutch
)

/** The title or the original title equals the text, ignoring case. */
private fun titleEquals(text: String) =
    (Movie_.primaryTitle.folded eqIgnoreCase text) or (Movie_.originalTitle.folded eqIgnoreCase text)

/** The title or the original title starts with the text, ignoring case. */
private fun titleStartsWith(text: String) =
    (Movie_.primaryTitle.folded startsWithIgnoreCase text) or (Movie_.originalTitle.folded startsWithIgnoreCase text)

/**
 * The credits a movie is found by: the leads, billed first to third, and the
 * directors. The partial index of migration V1 holds exactly these rows.
 */
private fun leadOrDirector() =
    (Principal_.ordering lessEq 3) or (Principal_.category eq "director")
