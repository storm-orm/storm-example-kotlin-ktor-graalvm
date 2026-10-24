package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import st.orm.demo.imdb.service.ImdbExport
import st.orm.demo.imdb.service.ImdbExportRow
import java.time.LocalDate

class ImdbExportTest {

    @Test
    fun `a ratings export gives the id, the day rated and the rating`() {
        val csv = "﻿Const,Your Rating,Date Rated,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors\r\n" +
            "tt0133093,9,2021-03-14,The Matrix,The Matrix,https://www.imdb.com/title/tt0133093,Movie,8.7,136,1999,\"Action, Sci-Fi\",2000000,1999-03-24,\"Lana Wachowski, Lilly Wachowski\"\r\n" +
            "tt0234215,5,2021-03-15,The Matrix Reloaded,The Matrix Reloaded,https://www.imdb.com/title/tt0234215,Movie,7.2,138,2003,\"Action, Sci-Fi\",600000,2003-05-15,\"Lana Wachowski, Lilly Wachowski\"\r\n"

        assertEquals(
            listOf(
                ImdbExportRow("tt0133093", LocalDate.of(2021, 3, 14), 9),
                ImdbExportRow("tt0234215", LocalDate.of(2021, 3, 15), 5)
            ),
            ImdbExport.parse(csv)
        )
    }

    @Test
    fun `a list export gives the day rated where there is one, else the day added`() {
        val csv = """
            Position,Const,Created,Modified,Description,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors,Your Rating,Date Rated
            1,tt0111161,2019-01-05,2019-01-05,"Hope, ""the"" best
            thing",The Shawshank Redemption,The Shawshank Redemption,https://www.imdb.com/title/tt0111161,Movie,9.3,142,1994,Drama,2900000,1994-10-14,Frank Darabont,10,2019-02-01
            2,tt0110912,2019-01-06,2019-01-06,,Pulp Fiction,Pulp Fiction,https://www.imdb.com/title/tt0110912,Movie,8.9,154,1994,"Crime, Drama",2200000,1994-10-14,Quentin Tarantino,,
        """.trimIndent()

        assertEquals(
            listOf(
                // A description with a comma, quotes and a line break stays one field.
                ImdbExportRow("tt0111161", LocalDate.of(2019, 2, 1), 10),
                ImdbExportRow("tt0110912", LocalDate.of(2019, 1, 6), null)
            ),
            ImdbExport.parse(csv)
        )
    }

    @Test
    fun `a file without the id column is no export`() {
        assertThrows<IllegalArgumentException> { ImdbExport.parse("name,email\nAlice,alice@example.com\n") }
        assertThrows<IllegalArgumentException> { ImdbExport.parse("") }
    }
}
