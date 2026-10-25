package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Genre
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.Rating
import st.orm.demo.imdb.service.MovieDetail
import st.orm.demo.imdb.service.MovieListState
import java.math.BigDecimal

class MovieDetailTest {

    private val matrix = Movie("tt0133093", "The Matrix", "The Matrix", startYear = 1999, runtimeMinutes = 136)

    @Test
    fun `the caption is the movie in a line, with what the movie has`() {
        val detail = MovieDetail(
            movie = matrix,
            rating = Rating(matrix, BigDecimal("8.7"), 2_000_000),
            genres = listOf(Genre(1, "Action"), Genre(3, "Sci-Fi")),
            directors = emptyList(),
            languages = emptyList(),
            cast = emptyList(),
            relatedMovies = emptyList(),
            lists = MovieListState(onWatchlist = false, seen = false)
        )
        assertEquals("136 min · Action, Sci-Fi · ★ 8.7 from 2,000,000 votes", detail.caption)

        val bare = detail.copy(movie = matrix.copy(runtimeMinutes = null), rating = null, genres = emptyList())
        assertEquals("", bare.caption)
    }
}
