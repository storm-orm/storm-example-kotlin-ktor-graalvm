package st.orm.demo.imdb.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.ScreenshotAnimations
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.regex.Pattern

/**
 * Interface tests against the running application (./gradlew bootRun, then
 * ./gradlew e2eTest). They exercise the user-facing behavior: navigation,
 * search auto-complete, infinite scrolling, the watchlist cycle, posters,
 * and overall visual quality.
 */
@Tag("e2e")
class StormMoviesInterfaceTest {

    private lateinit var context: BrowserContext
    private lateinit var page: Page

    @BeforeEach
    fun createPage() {
        context = browser.newContext(Browser.NewContextOptions().setViewportSize(1280, 900))
        page = context.newPage()
    }

    @AfterEach
    fun closePage() {
        context.close()
    }

    /* ── Home page ────────────────────────────────────────────────── */

    @Test
    fun `home page shows search, featured movie, top 10, and genre navigation`() {
        page.navigate("$baseUrl/")

        assertThat(page.locator(".navbar [data-search-input]")).isVisible()
        assertThat(page.locator(".search-hero [data-search-input]")).isVisible()

        // A new visitor's featured movie is one of the catalogue's picks.
        assertThat(page.locator(".hero-title")).isVisible()
        assertThat(page.locator(".hero-eyebrow")).hasText("Featured")
        assertThat(page.locator(".hero .poster")).isVisible()

        assertThat(page.locator("#top-rated-section .movie-card")).hasCount(10)

        val genreChips = page.locator(".genre-bar .genre-chip")
        assertTrue(genreChips.count() >= 10) { "Expected a well-filled genre bar" }
        assertThat(genreChips.first().locator(".count")).hasText(Pattern.compile("[\\d,]+"))
    }

    /* ── Search auto-complete ─────────────────────────────────────── */

    @Test
    fun `auto-complete suggests movies and navigates to the movie page`() {
        page.navigate("$baseUrl/")
        val searchInput = page.locator(".navbar [data-search-input]")
        searchInput.pressSequentially("matrix", Locator.PressSequentiallyOptions().setDelay(60.0))

        val movieSuggestion = page.locator(".navbar .suggestion:has(.suggestion-type-movie)").first()
        assertThat(movieSuggestion).isVisible()
        assertThat(movieSuggestion.locator(".suggestion-type")).hasText("Movie")
        assertThat(movieSuggestion.locator(".suggestion-text")).containsText(Pattern.compile("matrix", Pattern.CASE_INSENSITIVE))

        movieSuggestion.click()
        page.waitForURL(Pattern.compile(".*/movie/tt\\d+"))
        assertThat(page.locator(".movie-title")).containsText(Pattern.compile("matrix", Pattern.CASE_INSENSITIVE))
    }

    @Test
    fun `auto-complete suggests persons and navigates to the person page`() {
        page.navigate("$baseUrl/")
        val searchInput = page.locator(".navbar [data-search-input]")
        searchInput.pressSequentially("keanu reev", Locator.PressSequentiallyOptions().setDelay(60.0))

        val personSuggestion = page.locator(".navbar .suggestion:has(.suggestion-type-person)").first()
        assertThat(personSuggestion).isVisible()
        assertThat(personSuggestion.locator(".suggestion-type")).hasText("Person")

        personSuggestion.click()
        page.waitForURL(Pattern.compile(".*/person/nm\\d+"))
        assertThat(page.locator(".page-title")).containsText(Pattern.compile("keanu", Pattern.CASE_INSENSITIVE))
    }

    @Test
    fun `auto-complete is browsable with the arrow keys and opens the highlighted suggestion with enter`() {
        page.navigate("$baseUrl/")
        val searchInput = page.locator(".navbar [data-search-input]")
        searchInput.pressSequentially("matrix", Locator.PressSequentiallyOptions().setDelay(60.0))

        val suggestions = page.locator(".navbar .suggestion")
        assertThat(suggestions.first()).isVisible()
        // New results start without a highlight.
        assertThat(page.locator(".navbar .suggestion.active")).hasCount(0)

        searchInput.press("ArrowDown")
        assertThat(suggestions.nth(0)).hasClass(Pattern.compile("\\bactive\\b"))
        assertThat(searchInput).hasAttribute("aria-activedescendant", suggestions.nth(0).getAttribute("id"))
        searchInput.press("ArrowDown")
        assertThat(suggestions.nth(1)).hasClass(Pattern.compile("\\bactive\\b"))
        assertThat(page.locator(".navbar .suggestion.active")).hasCount(1)
        searchInput.press("ArrowUp")
        assertThat(suggestions.nth(0)).hasClass(Pattern.compile("\\bactive\\b"))
        // Up from the first suggestion wraps to the last.
        searchInput.press("ArrowUp")
        assertThat(suggestions.last()).hasClass(Pattern.compile("\\bactive\\b"))
        searchInput.press("ArrowDown")
        assertThat(suggestions.nth(0)).hasClass(Pattern.compile("\\bactive\\b"))

        val href = suggestions.nth(0).getAttribute("href")
        searchInput.press("Enter")
        page.waitForURL("$baseUrl$href")
        assertThat(page.locator(".movie-title")).containsText(Pattern.compile("matrix", Pattern.CASE_INSENSITIVE))
    }

    @Test
    fun `the find shortcut focuses the search box`() {
        val shortcut = if (System.getProperty("os.name").lowercase().contains("mac")) "Meta+f" else "Control+f"
        page.navigate("$baseUrl/")
        page.keyboard().press(shortcut)
        assertThat(page.locator(".search-hero [data-search-input]")).isFocused()

        page.navigate("$baseUrl/top")
        page.keyboard().press(shortcut)
        assertThat(page.locator(".navbar [data-search-input]")).isFocused()
    }

    @Test
    fun `enter without a highlighted suggestion opens the search page`() {
        page.navigate("$baseUrl/")
        val searchInput = page.locator(".navbar [data-search-input]")
        searchInput.pressSequentially("matrix", Locator.PressSequentiallyOptions().setDelay(60.0))
        assertThat(page.locator(".navbar .suggestion").first()).isVisible()

        searchInput.press("Enter")
        page.waitForURL(Pattern.compile(".*/search\\?query=matrix"))
    }

    /* ── Search results + infinite scroll ─────────────────────────── */

    @Test
    fun `search results show movies and persons and load more on scroll`() {
        page.navigate("$baseUrl/search?query=love")

        assertThat(page.locator("#movie-results-section .section-title")).hasText("Movies")
        assertThat(page.locator("#person-results-section .section-title")).hasText("People")
        assertThat(page.locator("#movie-results .movie-card").first()).isVisible()
        assertThat(page.locator("#person-results .person-card").first()).isVisible()

        val initialCount = page.locator("#movie-results .movie-card").count()
        page.locator("#movie-results-section [data-infinite-scroll]").scrollIntoViewIfNeeded()
        // The next keyset window is appended automatically.
        assertThat(page.locator("#movie-results .movie-card").nth(initialCount)).isVisible()
        assertTrue(page.locator("#movie-results .movie-card").count() > initialCount)
    }

    /* ── Browse by genre + infinite scroll ────────────────────────── */

    @Test
    fun `browsing a genre loads more movies on scroll`() {
        page.navigate("$baseUrl/browse/Drama")

        assertThat(page.locator(".page-title")).hasText("Drama")
        assertThat(page.locator(".page-subtitle")).containsText(Pattern.compile("[\\d,]+ movies"))

        val initialCount = page.locator("#browse-results .movie-card").count()
        assertEquals(24, initialCount)

        page.locator("[data-infinite-scroll]").scrollIntoViewIfNeeded()
        assertThat(page.locator("#browse-results .movie-card").nth(initialCount)).isVisible()
        assertTrue(page.locator("#browse-results .movie-card").count() > initialCount)
    }

    /* ── IMDb import ──────────────────────────────────────────────── */

    @Test
    fun `an IMDb export dropped on the seen page, or chosen with its plus, marks its movies as seen`() {
        page.navigate("$baseUrl/seen")
        // A browser without a seen list finds the import open, as a drop zone with its plus.
        assertThat(page.locator("[data-drop-zone]")).isVisible()
        assertThat(page.locator("[data-drop-zone] .drop-plus")).isVisible()
        assertThat(page.locator("[data-import-submit]")).isHidden()

        // Chosen in the file dialog, a file that is not an export: the page says so and imports nothing.
        page.locator("[data-import-file]").setInputFiles(FilePayload("contacts.csv", "text/csv", "name,email\nAlice,alice@example.com\n".toByteArray()))
        assertThat(page.locator("#import-failed")).isVisible()

        // Dropped on the zone, a ratings export: two movies, and a series the catalogue does not hold.
        val export = "Const,Your Rating,Date Rated,Title,Title Type\\n" +
            "tt0133093,9,2021-03-14,The Matrix,Movie\\n" +
            "tt0111161,10,2020-01-02,The Shawshank Redemption,Movie\\n" +
            "tt0903747,10,2019-05-01,Breaking Bad,TV Series\\n"
        page.evaluate("""
            const file = new File(["$export"], "ratings.csv", { type: "text/csv" });
            const transfer = new DataTransfer();
            transfer.items.add(file);
            document.querySelector("[data-drop-zone]").dispatchEvent(new DragEvent("drop", { dataTransfer: transfer, bubbles: true, cancelable: true }));
        """)

        assertThat(page.locator("#import-result")).containsText("2 movies marked as watched")
        assertThat(page.locator("#import-result")).containsText("1 titles skipped")
        assertThat(page.locator("#seen-results .movie-card-title")).containsText(arrayOf("The Matrix", "The Shawshank Redemption"))
        // Reloading the result does not upload the file again: the import answered with a redirect.
        assertTrue(page.url().contains("/seen?added=2"))
    }

    /* ── Seen tags ────────────────────────────────────────────────── */

    @Test
    fun `the seen page lists everything, or the check-ins alone under their month`() {
        page.navigate("$baseUrl/")
        page.request().post("$baseUrl/api/seen/tt0111161")
        page.request().post("$baseUrl/api/seen/tt0068646?when=earlier")

        page.navigate("$baseUrl/seen")
        assertThat(page.locator("#seen-results .movie-card")).hasCount(2)

        page.locator("#checkins-tab").click()
        page.waitForURL(Pattern.compile(".*/seen\\?checkins.*"))
        // Today's check-in under this month; the history stays out.
        assertThat(page.locator("#seen-results .day-group .section-title").first()).hasText(Pattern.compile("^[A-Z][a-z]+ \\d{4}$"))
        assertThat(page.locator("#seen-results .day-group a.movie-card[href='/movie/tt0111161']")).hasCount(1)
        assertThat(page.locator("#seen-results a.movie-card[href='/movie/tt0068646']")).hasCount(0)
    }

    @Test
    fun `lists of movies tag the ones seen, a check-in with its day, history with the checkmark alone`() {
        page.onConsoleMessage { println("console: " + it.text()) }
        page.onPageError { println("page error: $it") }
        page.navigate("$baseUrl/")
        page.request().post("$baseUrl/api/seen/tt0111161")

        // A ranked row: the tag sits beside the year; a check-in carries its day.
        page.navigate("$baseUrl/top")
        val row = page.locator("a.ranked-row[href='/movie/tt0111161']")
        assertThat(row.locator(".seen-tag")).hasText(Pattern.compile("✓ \\d{1,2} \\w{3} \\d{4}"))
        // History, marked as seen earlier, is the checkmark alone.
        page.request().post("$baseUrl/api/seen/tt0110912?when=earlier")
        page.reload()
        assertThat(page.locator("a.ranked-row[href='/movie/tt0110912'] .seen-tag")).hasText("✓")
        // A movie not seen has none.
        assertThat(page.locator("a.ranked-row[href='/movie/tt0068646'] .seen-tag")).hasCount(0)

        // Seen longer ago, here by an IMDb import, the tag and the movie page's button carry the day.
        page.request().post("$baseUrl/seen/import", RequestOptions.create().setMultipart(FormData.create()
            .set("export", FilePayload("ratings.csv", "text/csv", "Const,Your Rating,Date Rated\ntt0068646,9,2020-01-02\n".toByteArray()))))
        page.navigate("$baseUrl/top")
        assertThat(page.locator("a.ranked-row[href='/movie/tt0068646'] .seen-tag")).hasText("✓ 2 Jan 2020")
        // The movie's Watched button keeps its short label and says the day in its title.
        page.navigate("$baseUrl/movie/tt0068646")
        assertThat(page.locator("[data-seen-label]")).hasText("Watched")
        assertThat(page.locator("[data-list-toggle='seen']")).hasAttribute("title", "Watched 2 Jan 2020")
        page.navigate("$baseUrl/movie/tt0110912")
        assertThat(page.locator("[data-list-toggle='seen']")).hasAttribute("title", "Watched")

        // A poster card: the tag sits in the poster's corner, on the seen page as well.
        page.navigate("$baseUrl/seen")
        val posterTag = page.locator("#seen-results a.movie-card .poster .seen-tag").first()
        assertThat(posterTag).isVisible()
        // Shown, not merely present: the tag is what lies on top at its own centre, not the poster image.
        val box = posterTag.boundingBox()
        val onTop = page.evaluate("([x, y]) => !!document.elementFromPoint(x, y).closest('.seen-tag')", listOf(box.x + box.width / 2, box.y + box.height / 2))
        assertEquals(true, onTop)
    }

    @Test
    fun `a new visitor is welcomed toward the catch-up page, and told to sign in to keep the marks`() {
        page.navigate("$baseUrl/")
        assertThat(page.locator("#welcome")).isVisible()
        page.locator("#welcome a.welcome-button").click()
        page.waitForURL(Pattern.compile(".*/seen/catch-up"))
        val nudge = page.locator("[data-signin-nudge]")
        assertThat(nudge).isVisible()
        assertThat(nudge.locator("a")).hasAttribute("href", Pattern.compile("/signin\\?rd=.*catch-up"))

        // After five marks the note stands out.
        val cards = page.locator("#catch-up-results a.movie-card")
        for (i in 0 until 5) {
            cards.nth(i).click()
            assertThat(cards.nth(i).locator(".seen-tag")).isVisible()
        }
        assertThat(nudge).hasClass(Pattern.compile("urgent"))

        // Once something is marked, the welcome is gone.
        page.navigate("$baseUrl/")
        assertThat(page.locator("#welcome")).hasCount(0)
    }

    @Test
    fun `the catch-up page marks a movie seen before with a tap, and unmarks it`() {
        page.navigate("$baseUrl/seen/catch-up")
        val card = page.locator("#catch-up-results a.movie-card").first()
        val id = card.getAttribute("href").substringAfterLast('/')
        assertThat(card.locator(".seen-tag")).hasCount(0)

        // A tap marks it, as history: the checkmark alone, and the page stays.
        card.click()
        assertThat(card.locator(".seen-tag")).hasText("✓")
        assertTrue(page.url().endsWith("/seen/catch-up"))
        page.navigate("$baseUrl/movie/$id")
        assertThat(page.locator("[data-seen-label]")).hasText("Watched")

        // Another tap unmarks it.
        page.navigate("$baseUrl/seen/catch-up")
        page.locator("#catch-up-results a.movie-card[href='/movie/$id']").click()
        assertThat(page.locator("#catch-up-results a.movie-card[href='/movie/$id'] .seen-tag")).hasCount(0)
        assertThat(page.locator("[data-seen-count]")).hasText("0")

        // A search finds one movie to mark the same way, and the count follows.
        page.locator("[data-catch-up-search]").fill("matrix")
        val found = page.locator("#catch-up-search-results a.movie-card[href='/movie/tt0133093']")
        found.click()
        assertThat(found.locator(".seen-tag")).hasText("✓")
        assertThat(page.locator("[data-seen-count]")).hasText("1")

        // "Hide marked" lists only the movies still to go, filtered by the server.
        page.locator("[data-hide-marked]").check()
        page.waitForURL(Pattern.compile(".*/seen/catch-up\\?unmarked"))
        assertThat(page.locator("#catch-up-results a.movie-card").first()).isVisible()
        assertThat(page.locator("#catch-up-results a.movie-card[href='/movie/tt0133093']")).hasCount(0)
        assertThat(page.locator("[data-hide-marked]")).isChecked()
    }

    /* ── Insights ─────────────────────────────────────────────────── */

    @Test
    fun `the insights page fills in from the seen list`() {
        page.navigate("$baseUrl/insights")
        assertThat(page.locator("#insights-empty")).isVisible()

        // Five seen movies make the usual range known; the browser's account marks them.
        for (movieId in listOf("tt0133093", "tt0234215", "tt0111161", "tt0110912", "tt0068646")) {
            page.request().post("$baseUrl/api/seen/$movieId")
        }
        page.navigate("$baseUrl/insights")

        assertThat(page.locator("#insight-totals .stat-chip").first()).containsText("5")
        assertThat(page.locator("#ratings-card .histogram-bar")).not().hasCount(0)
        assertThat(page.locator("#ratings-card .histogram-curve polyline")).hasCount(1)
        assertThat(page.locator("#ratings-card .stat-note")).containsText("You usually watch movies rated")
        assertThat(page.locator("#seen-decades-card .bar-row")).not().hasCount(0)
        assertThat(page.locator("#seen-genres-card .bar-row")).not().hasCount(0)
        assertThat(page.locator("#highlights-section .highlight-card")).not().hasCount(0)
        page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e-screenshots/insights-filled.png")).setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED))

        // Filled in, the page still fits a tablet and a phone: the navbar, the histogram and the bars shrink with the screen.
        for (width in listOf(820, 390)) {
            page.setViewportSize(width, 900)
            val overflow = page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number
            assertTrue(overflow.toInt() <= 1) { "The insights page overflows a screen ${width}px wide" }
        }
        page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e-screenshots/mobile-insights-filled.png")).setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED))
    }

    /* ── Movie detail ─────────────────────────────────────────────── */

    @Test
    fun `a movie page can be shared, and the link unfurls into a card`() {
        page.navigate("$baseUrl/movie/tt0133093")

        // The card a chat or a timeline shows for the link: title, line, poster, the page's own URL.
        assertEquals("The Matrix (1999)", page.locator("meta[property='og:title']").getAttribute("content"))
        assertThat(page.locator("meta[property='og:description']")).hasAttribute("content", Pattern.compile("^136 min · .*★ \\d\\.\\d from [\\d,]+ votes"))
        assertThat(page.locator("meta[property='og:image']")).hasAttribute("content", Pattern.compile("/api/poster/tt0133093$"))
        assertThat(page.locator("meta[property='og:url']")).hasAttribute("content", Pattern.compile("/movie/tt0133093$"))

        // Without a share sheet, the button opens the menu: copy the link, post on X, email.
        val menu = page.locator("[data-share-menu]")
        assertThat(menu).isHidden()
        page.locator("[data-share]").click()
        assertThat(menu).isVisible()
        assertThat(menu.locator("[data-share-x]")).hasAttribute("href", Pattern.compile("^https://x\\.com/intent/post\\?text=The%20Matrix%20\\(1999\\)%20%C2%B7%20136%20min.*&url=.*%2Fmovie%2Ftt0133093$"))
        assertThat(menu.locator("[data-share-mail]")).hasAttribute("href", Pattern.compile("^mailto:\\?subject=The%20Matrix%20\\(1999\\)&body="))
    }

    @Test
    fun `movie detail shows info, cast, ambient backdrop, and related movies`() {
        page.navigate("$baseUrl/movie/tt0133093")

        assertThat(page.locator(".movie-title")).hasText("The Matrix")
        assertThat(page.locator(".rating-badge").first()).isVisible()
        assertThat(page.locator(".movie-genres .genre-chip").first()).isVisible()

        // Server renders the blurred poster; the sharp landscape backdrop
        // swaps in asynchronously — either way the style points at our API.
        val backdropStyle = page.locator("#movie-backdrop").getAttribute("style")
        assertTrue(backdropStyle.contains("/api/")) { "Backdrop uses an API image" }
        assertTrue(
            page.locator("#movie-backdrop").getAttribute("data-backdrop-source")
                .contains("/api/backdrop/tt0133093")
        )

        assertTrue(page.locator("#cast-section .credit-card").count() >= 4)
        assertThat(page.locator("#related-section .movie-card").first()).isVisible()

        // The Wikipedia plot summary loads asynchronously.
        assertThat(page.locator("#movie-description"))
            .isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(15_000.0))
        assertTrue(page.locator("#movie-description").textContent().length > 80)
    }

    /* ── Watchlist ────────────────────────────────────────────────── */

    @Test
    fun `the watchlist toggle persists across home and watchlist pages`() {
        page.navigate("$baseUrl/movie/tt0133093")
        val toggleButton = page.locator("[data-list-toggle='watchlist']")
        assertThat(page.locator("[data-watchlist-label]")).hasText("Watchlist")

        // Make the starting state deterministic: ensure OFF first.
        if (toggleButton.getAttribute("aria-pressed") == "true") {
            toggleButton.click()
            assertThat(toggleButton).hasAttribute("aria-pressed", "false")
        }

        toggleButton.click()
        assertThat(toggleButton).hasAttribute("aria-pressed", "true")
        assertThat(toggleButton).hasAttribute("title", "On your watchlist")

        page.navigate("$baseUrl/")
        assertThat(page.locator("#watchlist-section")).isVisible()
        assertThat(page.locator("#watchlist-section .movie-card-title").first()).hasText("The Matrix")

        page.navigate("$baseUrl/watchlist")
        assertThat(page.locator("#watchlist-results .movie-card-title").first()).hasText("The Matrix")

        // Toggle back off to leave no residue.
        page.navigate("$baseUrl/movie/tt0133093")
        toggleButton.click()
        assertThat(toggleButton).hasAttribute("aria-pressed", "false")
    }

    @Test
    fun `the watchlist page paginates once it has more than one page`() {
        val request = page.context().request()
        val movieIds = fetchSomeMovieIds(count = 13)

        movieIds.forEach { movieId -> request.post("$baseUrl/api/watchlist/$movieId") }
        try {
            page.navigate("$baseUrl/watchlist?page=1")
            assertEquals(12, page.locator("#watchlist-results .movie-card").count())
            assertThat(page.locator(".pagination .page-info")).containsText(Pattern.compile("Page 1 of \\d+"))

            page.locator(".pagination a", Page.LocatorOptions().setHasText("Next")).click()
            page.waitForURL(Pattern.compile(".*page=2.*"))
            assertTrue(page.locator("#watchlist-results .movie-card").count() >= 1)
        } finally {
            // Toggle everything back off.
            movieIds.forEach { movieId -> request.post("$baseUrl/api/watchlist/$movieId") }
        }
    }

    /* ── Seen list and account ────────────────────────────────────── */

    @Test
    fun `marking a movie seen takes it off the watchlist and lists it on the seen page`() {
        page.navigate("$baseUrl/movie/tt0133093")
        val watchlistButton = page.locator("[data-list-toggle='watchlist']")
        val seenButton = page.locator("[data-list-toggle='seen']")

        // Each browser context is a new account, so both lists start empty.
        assertThat(watchlistButton).hasAttribute("aria-pressed", "false")
        assertThat(seenButton).hasAttribute("aria-pressed", "false")
        assertThat(seenButton).hasAttribute("title", "Mark as watched")

        watchlistButton.click()
        assertThat(watchlistButton).hasAttribute("aria-pressed", "true")
        // Check-in opens the days of the past week; today it is, which the seen button's title shows with its day.
        page.locator("[data-check-in]").click()
        assertThat(page.locator("[data-check-in-day]")).hasCount(7)
        page.locator("[data-check-in-day]").first().click()
        assertThat(page.locator("[data-check-in-label]")).hasText("Checked in")
        assertThat(page.locator("[data-check-in]")).hasAttribute("title", "Checked in today")
        assertThat(seenButton).hasAttribute("aria-pressed", "true")
        assertThat(seenButton).hasAttribute("title", Pattern.compile("Watched \\d{1,2} \\w{3} \\d{4}"))
        assertThat(watchlistButton).hasAttribute("aria-pressed", "false")

        page.navigate("$baseUrl/seen")
        assertThat(page.locator("#seen-results .movie-card-title").first()).hasText("The Matrix")
        page.navigate("$baseUrl/watchlist")
        assertEquals(0, page.locator("#watchlist-results .movie-card").count())
        page.navigate("$baseUrl/")
        assertThat(page.locator("#seen-section .movie-card-title").first()).hasText("The Matrix")
    }

    @Test
    fun `a thumbs vote appears once a movie is seen and can be taken back`() {
        page.navigate("$baseUrl/movie/tt0133093")
        val votes = page.locator("[data-vote-buttons]")
        assertThat(votes).isHidden()

        page.markSeenEarlier()
        assertThat(page.locator("[data-seen-label]")).hasText("Watched")
        assertThat(votes).isVisible()

        val thumbsUp = page.locator("[data-vote='true']")
        thumbsUp.click()
        assertThat(thumbsUp).hasClass(Pattern.compile("active"))
        page.reload()
        assertThat(page.locator("[data-vote='true']")).hasClass(Pattern.compile("active"))
        page.locator("[data-vote='true']").click()
        assertThat(page.locator("[data-vote='true']")).not().hasClass(Pattern.compile("active"))
    }

    @Test
    fun `the For You page shows picks with reasons, personal once something is seen`() {
        page.navigate("$baseUrl/for-you")
        assertThat(page.locator(".page-title")).hasText("For You")
        assertTrue(page.locator("#for-you-results .movie-card").count() > 0)
        assertThat(page.locator("#for-you-results .movie-card-reasons span").first()).isVisible()
        assertThat(page.locator(".page-subtitle")).containsText("whole catalogue")

        // Seeing The Matrix makes the picks personal, computed in the background.
        page.navigate("$baseUrl/movie/tt0133093")
        page.markSeenEarlier()
        assertThat(page.locator("[data-seen-label]")).hasText("Watched")
        page.waitForCondition({
            page.navigate("$baseUrl/for-you")
            page.locator(".page-subtitle").textContent().contains("you have watched")
        }, Page.WaitForConditionOptions().setTimeout(30_000.0))
        assertThat(page.locator("#for-you-results .movie-card-reasons span").first()).containsText("Because you saw The Matrix")

        // The home page features one of the picks.
        page.navigate("$baseUrl/")
        assertThat(page.locator(".hero-eyebrow")).hasText("Featured · For you")
        assertThat(page.locator(".hero-reasons")).hasCount(0)
        page.navigate("$baseUrl/for-you")

        // Opening a pick from here counts as a recommendation that led somewhere.
        page.locator("#for-you-results .movie-card").first().click()
        page.waitForURL(Pattern.compile(".*/movie/.*via=for-you.*"))
        assertThat(page.locator(".movie-title")).isVisible()
    }

    @Test
    fun `not interested removes a pick and keeps it out`() {
        page.navigate("$baseUrl/movie/tt0133093")
        page.markSeenEarlier()
        assertThat(page.locator("[data-seen-label]")).hasText("Watched")
        page.waitForCondition({
            page.navigate("$baseUrl/for-you")
            page.locator(".page-subtitle").textContent().contains("you have watched")
        }, Page.WaitForConditionOptions().setTimeout(30_000.0))

        val first = page.locator("#for-you-results .recommended").first()
        val dismissedId = first.getAttribute("data-movie-id")
        first.hover()
        first.locator("[data-dismiss]").click()
        assertThat(page.locator("#for-you-results .recommended[data-movie-id='$dismissedId']")).hasCount(0)

        page.reload()
        assertThat(page.locator("#for-you-results .recommended").first()).isVisible()
        assertThat(page.locator("#for-you-results .recommended[data-movie-id='$dismissedId']")).hasCount(0)
    }

    @Test
    fun `the lists belong to the browser's account`() {
        page.navigate("$baseUrl/movie/tt0133093")
        page.locator("[data-list-toggle='watchlist']").click()
        assertThat(page.locator("[data-list-toggle='watchlist']")).hasAttribute("aria-pressed", "true")

        // Another browser, another account: an empty watchlist.
        val otherContext = browser.newContext()
        try {
            val other = otherContext.newPage()
            other.navigate("$baseUrl/watchlist")
            assertEquals(0, other.locator("#watchlist-results .movie-card").count())
            assertThat(other.locator(".nav-signin")).isVisible()
        } finally {
            otherContext.close()
        }
    }

    @Test
    fun `friends and clubs share the community pages, both for signed-in visitors`() {
        page.navigate("$baseUrl/friends")
        assertThat(page.locator(".page-title")).hasText("Community")
        assertThat(page.locator("#friends-tab")).hasClass(Pattern.compile("active"))
        assertThat(page.locator("#friends-signin")).isVisible()

        page.locator("#clubs-tab").click()
        page.waitForURL(Pattern.compile(".*/clubs$"))
        assertThat(page.locator("#clubs-tab")).hasClass(Pattern.compile("active"))
        assertThat(page.locator("#clubs-signin a")).hasAttribute("href", Pattern.compile("/signin\\?rd=.*clubs"))
    }

    @Test
    fun `the sign-in page offers the configured providers or says there are none`() {
        page.navigate("$baseUrl/signin?rd=/watchlist")
        assertThat(page.locator(".page-title")).hasText("Sign in")
        val providers = page.locator(".signin-button").count()
        if (providers == 0) {
            assertThat(page.locator(".empty-state")).containsText("No sign-in providers")
        } else {
            // The page to return to travels along, encoded or not.
            val href = java.net.URLDecoder.decode(page.locator(".signin-button").first().getAttribute("href"), Charsets.UTF_8)
            assertTrue(href.contains("rd=/watchlist")) { href }
        }
    }

    /* ── Recently viewed ──────────────────────────────────────────── */

    @Test
    fun `recently viewed is the browser's own`() {
        // The browser gets its account on the first page; the views after it are its own.
        page.navigate("$baseUrl/")
        page.navigate("$baseUrl/movie/tt0111161")
        page.navigate("$baseUrl/movie/tt0068646")

        page.navigate("$baseUrl/")
        assertThat(page.locator("#recently-viewed-section .movie-card-title").first()).hasText("The Godfather")
        assertThat(page.locator("#recently-viewed-section .movie-card-title", Page.LocatorOptions())
            .filter(Locator.FilterOptions().setHasText("The Shawshank Redemption")).first()).isVisible()

        // Another browser sees none of these views as its own.
        val other = browser.newContext()
        try {
            val otherPage = other.newPage()
            otherPage.navigate("$baseUrl/")
            assertThat(otherPage.locator("#recently-viewed-section")).hasCount(0)
        } finally {
            other.close()
        }
    }

    /* ── Person detail ────────────────────────────────────────────── */

    @Test
    fun `clicking a cast member shows the filmography with statistics`() {
        page.navigate("$baseUrl/movie/tt0133093")
        val firstCastMember = page.locator("#cast-section .credit-card").first()
        val castMemberName = firstCastMember.locator(".person-card-name").textContent()
        firstCastMember.click()

        page.waitForURL(Pattern.compile(".*/person/nm\\d+"))
        assertThat(page.locator(".page-title")).hasText(castMemberName)
        assertTrue(page.locator("#person-statistics .stat-chip").count() >= 1)
        assertTrue(page.locator("#filmography-section .ranked-row").count() >= 1)
        assertThat(page.locator("#filmography-section .rating-badge").first()).isVisible()

        // Personal details: the headshot header and the Wikipedia biography.
        assertThat(page.locator(".person-header .avatar-large")).isVisible()
        assertThat(page.locator("#person-bio"))
            .isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(15_000.0))
    }

    /* ── Statistics ───────────────────────────────────────────────── */

    @Test
    fun `the statistics page renders all aggregate sections`() {
        page.navigate("$baseUrl/statistics")

        assertEquals(4, page.locator("#catalogue-totals .stat-chip").count())
        assertTrue(page.locator("#decades-card .bar-row").count() >= 5)
        assertTrue(page.locator("#genres-card .stat-row").count() >= 5)
        assertEquals(10, page.locator("#actors-card .stat-row").count())
        // Bars are actually scaled: at least one bar is wider than 50%.
        val widestBar = page.locator("#decades-card .bar-fill")
            .evaluateAll("bars => Math.max(...bars.map(bar => parseFloat(bar.style.width)))")
        assertTrue((widestBar as Number).toDouble() > 50.0)
    }

    /* ── Posters ──────────────────────────────────────────────────── */

    @Test
    fun `the poster endpoint redirects to a CDN image and the poster renders`() {
        val response = page.context().request().get(
            "$baseUrl/api/poster/tt0133093",
            RequestOptions.create().setMaxRedirects(0)
        )
        assertEquals(302, response.status())
        val location = response.headers()["location"] ?: ""
        assertTrue(location.startsWith("https://")) { "Expected a CDN redirect, got: $location" }

        val backdropResponse = page.context().request().get(
            "$baseUrl/api/backdrop/tt0133093",
            RequestOptions.create().setMaxRedirects(0)
        )
        assertEquals(302, backdropResponse.status())

        page.navigate("$baseUrl/movie/tt0133093")
        val poster = page.locator(".movie-hero-poster img")
        page.waitForCondition { poster.evaluate("img => img.complete && img.naturalWidth > 0") as Boolean }
    }

    @Test
    fun `movies without posters show the styled gradient placeholder`() {
        // Deterministic poster failure: block the poster endpoint entirely.
        page.route("**/api/poster/**") { route -> route.abort() }
        page.navigate("$baseUrl/browse/Drama")

        val firstPoster = page.locator("#browse-results .movie-card .poster").first()
        assertThat(firstPoster.locator("img")).isHidden()
        assertThat(firstPoster.locator(".poster-placeholder span")).isVisible()
    }

    /* ── UI polish ────────────────────────────────────────────────── */

    @Test
    fun `pages are styled, do not overflow, and screenshots are captured`() {
        val screenshots = Paths.get("build/e2e-screenshots")
        for ((path, name) in listOf(
            "/" to "home",
            "/movie/tt0133093" to "movie",
            "/person/nm0000206" to "person",
            "/statistics" to "statistics",
            "/insights" to "insights"
        )) {
            page.navigate("$baseUrl$path")
            // The stylesheet loaded: the dark theme background is applied.
            val background = page.evaluate("getComputedStyle(document.body).backgroundColor")
            assertEquals("rgb(11, 13, 20)", background)
            // No horizontal overflow.
            val overflow = page.evaluate(
                "document.documentElement.scrollWidth - document.documentElement.clientWidth"
            ) as Number
            assertTrue(overflow.toInt() <= 1) { "Horizontal overflow on $path" }
            page.screenshot(
                Page.ScreenshotOptions()
                    .setPath(screenshots.resolve("$name.png"))
                    .setFullPage(false)
                    // Fast-forward CSS animations so screenshots show settled state.
                    .setAnimations(ScreenshotAnimations.DISABLED)
            )
        }
    }

    @Test
    fun `every page fits a phone screen and the search box serves it`() {
        // A phone widens its layout to the widest content, so a page that does not fit is shown
        // zoomed out rather than overflowing: the layout width, not the scroll width, tells.
        val phone = browser.newContext(
            Browser.NewContextOptions().setViewportSize(390, 844).setIsMobile(true).setHasTouch(true)
        )
        try {
            val mobile = phone.newPage()
            val screenshots = Paths.get("build/e2e-screenshots")
            for ((path, name) in listOf(
                "/" to "home", "/top" to "top", "/watchlist" to "watchlist", "/seen" to "seen",
                "/signin" to "signin", "/statistics" to "statistics", "/insights" to "insights",
                "/seen/catch-up" to "catch-up", "/friends" to "friends", "/clubs" to "clubs",
                "/search?query=matrix" to "search", "/movie/tt0133093" to "movie",
                "/person/nm0000206" to "person", "/browse/Drama" to "browse"
            )) {
                mobile.navigate("$baseUrl$path")
                val width = mobile.evaluate("window.innerWidth") as Number
                assertEquals(390, width.toInt()) { "$path lays out wider than the phone screen" }
                mobile.screenshot(
                    Page.ScreenshotOptions().setPath(screenshots.resolve("mobile-$name.png"))
                        .setAnimations(ScreenshotAnimations.DISABLED)
                )
            }
            // The navbar search box spans the screen, and its suggestions with it.
            mobile.navigate("$baseUrl/top")
            val input = mobile.locator(".navbar [data-search-input]")
            assertThat(input).isVisible()
            input.fill("matrix")
            val suggestions = mobile.locator(".navbar [data-search-suggestions]")
            assertThat(suggestions.locator(".suggestion").first()).isVisible()
            val box = suggestions.boundingBox()
            assertTrue(box.width >= 340) { "Suggestions are ${box.width}px wide on a 390px screen" }
        } finally {
            phone.close()
        }
    }

    /* ── Helpers ──────────────────────────────────────────────────── */

    /** Marks the movie on the page as watched, without a day: the Watched button. */
    private fun Page.markSeenEarlier() {
        locator("[data-list-toggle='seen']").click()
    }

    private fun fetchSomeMovieIds(count: Int): List<String> {
        val response = page.context().request().get("$baseUrl/api/search/movies?query=the")
        val ids = Regex("\"id\":\"(tt\\d+)\"").findAll(response.text()).map { it.groupValues[1] }.toList()
        assertTrue(ids.size >= count) { "Expected at least $count movies for the pagination test" }
        return ids.take(count)
    }

    companion object {
        private val baseUrl: String = System.getProperty("app.baseUrl", "http://localhost:8080")
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

        @JvmStatic
        @BeforeAll
        fun startBrowser() {
            playwright = Playwright.create()
            browser = playwright.chromium().launch()
        }

        @JvmStatic
        @AfterAll
        fun stopBrowser() {
            browser.close()
            playwright.close()
        }
    }
}
