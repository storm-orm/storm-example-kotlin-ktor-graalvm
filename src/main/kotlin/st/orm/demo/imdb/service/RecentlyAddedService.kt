package st.orm.demo.imdb.service

import st.orm.Window
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieAddition
import st.orm.demo.imdb.repository.MovieAdditionRepository
import st.orm.template.transaction
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of additions: the movies that joined the catalogue that day, as the window orders them. */
data class AdditionDay(
    val label: String,
    val movies: List<Movie>
)

/** The recently added page: the days of the first window, and the cursor of the next window. */
data class RecentlyAddedView(
    val days: List<AdditionDay>,
    val nextCursor: String?
)

/** The day the movie joined the catalogue, as the page heads it: "23 October 2026", in UTC. */
fun MovieAddition.additionDay(): String = ADDITION_DAY.format(addedAt)

private val ADDITION_DAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

class RecentlyAddedService(private val movieAdditionRepository: MovieAdditionRepository) {

    /**
     * The recently added page: the first window of the additions, newest
     * first and the most voted first within a sync run's additions, grouped
     * by the day. A day may continue in the next window; the client appends
     * to it.
     */
    suspend fun recentlyAdded(): RecentlyAddedView = transaction(readOnly = true) {
        val window = movieAdditionRepository.scrollAdditions(MovieAdditionRepository.newestFirst(PAGE_SIZE))
        RecentlyAddedView(
            days = window.content()
                .groupBy { it.additionDay() }
                .map { (day, additions) -> AdditionDay(day, additions.map { it.movie }) },
            nextCursor = window.nextCursor()
        )
    }

    /**
     * The next window of additions — same cursor contract as the other
     * scrolls: null requests the first window, any other value resumes
     * where the client left off.
     */
    suspend fun scroll(cursor: String?): Window<MovieAddition> = transaction(readOnly = true) {
        val scrollable = if (cursor != null) {
            MovieAdditionRepository.newestFirst(PAGE_SIZE).from(cursor)
        } else {
            MovieAdditionRepository.newestFirst(PAGE_SIZE)
        }
        movieAdditionRepository.scrollAdditions(scrollable)
    }

    companion object {
        private const val PAGE_SIZE = 24
    }
}
