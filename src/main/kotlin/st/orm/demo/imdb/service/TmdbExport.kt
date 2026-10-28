package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Source
import kotlin.math.roundToInt

/**
 * Reads the CSV file TMDB exports for a user's ratings, watchlist or lists,
 * which TMDB sends by mail when asked for on the page. The columns are found
 * by name in the header, as for [ImdbExport]: the movie's TMDB id ("TMDb
 * ID"), its IMDb id where the export has one ("IMDb ID"), which names the
 * movie in its place, the day it was rated or added, and the user's rating,
 * which TMDB keeps in halves of a point and is rounded here. Rows of series and episodes ("Type") are left
 * out, as the catalogue holds movies only.
 */
object TmdbExport {

    private const val TMDB_ID = "tmdb id"
    private const val IMDB_ID = "imdb id"
    private const val TYPE = "type"
    private val DATES = listOf("date rated", "your rating date", "date added", "created", "added")
    private val RATINGS = listOf("your rating", "rating")

    /** Whether a file is a TMDB export, by its header. */
    fun matches(text: String): Boolean = TMDB_ID in header(text)

    /** The movies of an export, in its order. Throws [IllegalArgumentException] for a file that is not one. */
    fun parse(text: String): List<ExportRow> {
        val (raw, records) = Csv.read(text)
        val header = raw.map { it.lowercase() }
        val tmdbId = header.indexOf(TMDB_ID)
        require(tmdbId >= 0) { "Not a TMDB export: the file has no TMDb ID column." }
        val imdbId = header.indexOf(IMDB_ID)
        val type = header.indexOf(TYPE)
        val date = DATES.map(header::indexOf).firstOrNull { it >= 0 } ?: -1
        val rating = RATINGS.map(header::indexOf).firstOrNull { it >= 0 } ?: -1
        return records
            .filter { record -> record.getOrNull(type)?.trim()?.lowercase().let { it == null || it.isEmpty() || it == "movie" } }
            .mapNotNull { record ->
                val imdb = record.getOrNull(imdbId)?.trim()?.takeIf { it.startsWith("tt") }
                val tmdb = record.getOrNull(tmdbId)?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ExportRow(
                    source = if (imdb != null) Source.IMDB else Source.TMDB,
                    externalId = imdb ?: tmdb ?: return@mapNotNull null,
                    date = Csv.date(record.getOrNull(date)),
                    rating = record.getOrNull(rating)?.trim()?.toDoubleOrNull()?.roundToInt()?.takeIf { it in 1..10 }
                )
            }
    }

    private fun header(text: String): List<String> =
        Csv.read(text.substringBefore('\n')).first.map { it.lowercase() }
}
