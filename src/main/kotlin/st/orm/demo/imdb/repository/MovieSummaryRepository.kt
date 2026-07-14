package st.orm.demo.imdb.repository

import st.orm.Scrollable
import st.orm.Window
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.MovieGenre
import st.orm.demo.imdb.model.MovieGenre_
import st.orm.demo.imdb.model.MovieSummary
import st.orm.demo.imdb.model.MovieSummary_
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.model.Rating_
import st.orm.repository.ProjectionRepository
import st.orm.template.eq

interface MovieSummaryRepository : ProjectionRepository<MovieSummary, String> {

    /**
     * Case-insensitive title search with keyset scrolling. The projection
     * keeps the SELECT to the three columns the result grid actually shows.
     * The returned Window offers both navigation modes: typed
     * next()/previous() Scrollables for server-side code, and opaque
     * nextCursor()/previousCursor() strings for clients.
     */
    fun searchByTitle(query: String, scrollable: Scrollable<MovieSummary>): Window<MovieSummary> {
        val pattern = "%$query%"
        return select()
            .where { "LOWER(${MovieSummary_.primaryTitle}) LIKE LOWER($pattern)" }
            .scroll(scrollable)
    }

    /**
     * Title suggestions for the search auto-complete, ranked by popularity
     * (vote count) so well-known movies surface first. Rating's foreign key
     * references the Movie entity; the join onto this projection resolves
     * automatically because both map the same table.
     */
    fun findTitleSuggestions(query: String, limit: Int): List<MovieSummary> {
        val pattern = "%$query%"
        return select()
            .innerJoin<Rating>().on<MovieSummary>()
            .where { "LOWER(${MovieSummary_.primaryTitle}) LIKE LOWER($pattern)" }
            .orderByDescendingAny(Rating_.voteCount)
            .limit(limit)
            .resultList
    }

    /**
     * All movies in a genre with keyset scrolling. The junction table has a
     * composite key and cannot be scrolled directly, so the scroll runs on
     * the movie's simple primary key with a JOIN through the junction table,
     * resolved automatically against the projection by table.
     */
    fun scrollByGenre(genre: Genre, scrollable: Scrollable<MovieSummary>) =
        select()
            .innerJoin<MovieGenre>().on<MovieSummary>()
            .whereAny(MovieGenre_.genre eq genre)
            .scroll(scrollable)
}
