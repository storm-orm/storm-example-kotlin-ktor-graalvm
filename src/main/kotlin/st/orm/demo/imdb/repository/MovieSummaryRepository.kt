package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.MovieSummary
import st.orm.demo.imdb.model.MovieSummary_
import st.orm.demo.imdb.model.Person
import st.orm.demo.imdb.model.Person_
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.repository.ProjectionRepository
import st.orm.template.eq
import st.orm.template.search

interface MovieSummaryRepository : ProjectionRepository<MovieSummary, String> {

    /**
     * Movie search with keyset scrolling. Every word of the query must occur
     * in the title, the original title or the name of someone credited, such
     * as an actor or the director, ignoring case, so "matrix reloaded" finds The Matrix Reloaded, "fabuleux destin"
     * finds Amélie and "matrix keanu" finds both Matrix movies; a quoted
     * phrase counts as one word, and % or _ match themselves. The projection
     * keeps the SELECT to the columns the result grid needs.
     * The names come from the credits, one row per credit, and DISTINCT
     * folds them back into one row per movie. The joins are LEFT joins, so a
     * movie without credits is still found by its title.
     * The returned Window offers both navigation modes: typed
     * next()/previous() Scrollables for server-side code, and opaque
     * nextCursor()/previousCursor() strings for clients.
     */
    fun searchMovies(query: String, scrollable: Scrollable<MovieSummary>): Window<MovieSummary> =
        select()
            .distinct()
            .leftJoin<Principal>().on<MovieSummary>()
            .leftJoin<Person>().on<Principal>()
            .where(search(query, MovieSummary_.primaryTitle, MovieSummary_.originalTitle, Person_.primaryName))
            .narrow<MovieSummary>()
            .scroll(scrollable)

    /**
     * Movie suggestions for the search auto-complete, matched as
     * [searchMovies] matches them and ranked by popularity (vote count) so
     * well-known movies surface first. Rating's foreign key references the
     * Movie entity; the join onto this projection resolves automatically
     * because both map the same table. A DISTINCT query can only order by
     * the columns it selects, so the credits fold back into one row per
     * movie with a GROUP BY that includes the vote count.
     */
    fun findMovieSuggestions(query: String, limit: Int): List<MovieSummary> =
        select()
            .innerJoin<Rating>().on<MovieSummary>()
            .leftJoin<Principal>().on<MovieSummary>()
            .leftJoin<Person>().on<Principal>()
            .where(search(query, MovieSummary_.primaryTitle, MovieSummary_.originalTitle, Person_.primaryName))
            .groupBy(MovieSummary_.id, Rating_.voteCount)
            .orderByDescending(Rating_.voteCount)
            .limit(limit)
            .resultList

    /**
     * All movies in a genre with keyset scrolling. The junction table has a
     * composite key and cannot be scrolled directly, so the scroll runs on
     * the movie's simple primary key with a JOIN through the junction table,
     * resolved automatically against the projection by table. The scroll key
     * has to identify one row of the projection, which a key on the junction
     * table would not.
     */
    fun scrollByGenre(genre: Genre, scrollable: Scrollable<MovieSummary>) =
        select()
            .innerJoin<MovieGenre>().on<MovieSummary>()
            .where(MovieGenre_.genre eq genre)
            .narrow<MovieSummary>()
            .scroll(scrollable)
}
