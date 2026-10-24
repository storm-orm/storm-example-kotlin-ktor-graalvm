package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Source
import java.time.LocalDate

/**
 * One title of an export, by the id a source gives it ([Source]): the day it
 * was rated or added to the list, and the user's rating, 1 to 10, if any.
 * Every format reads into these, and the import finds the movies by their
 * sources, so a new format is a parser and nothing more.
 */
data class ExportRow(val source: Source, val externalId: String, val date: LocalDate?, val rating: Int?)

/**
 * Reads the CSV file IMDb exports for a user's ratings or for one of their
 * lists. IMDb offers no API and no sign-in for other sites, so the export is
 * how a user brings their history along. The two kinds of export order their
 * columns differently, so the columns are found by name in the header: the
 * title's id ("Const"), the day it was rated ("Date Rated") or else added to
 * the list ("Created"), and the user's rating ("Your Rating"). The rest, the
 * title, the genres and the IMDb rating, the catalogue has already.
 */
object ImdbExport {

    private const val ID = "Const"
    private const val DATE_RATED = "Date Rated"
    private const val DATE_ADDED = "Created"
    private const val RATING = "Your Rating"

    /** The titles of an export, in its order. Throws [IllegalArgumentException] for a file that is not one. */
    fun parse(text: String): List<ExportRow> {
        val (header, records) = Csv.read(text)
        val id = header.indexOf(ID)
        require(id >= 0) { "Not an IMDb export: the file has no $ID column." }
        val dateRated = header.indexOf(DATE_RATED)
        val dateAdded = header.indexOf(DATE_ADDED)
        val rating = header.indexOf(RATING)
        return records
            .filter { it.getOrNull(id)?.startsWith("tt") == true }
            .map { record ->
                ExportRow(
                    source = Source.IMDB,
                    externalId = record[id].trim(),
                    date = Csv.date(record.getOrNull(dateRated)) ?: Csv.date(record.getOrNull(dateAdded)),
                    rating = record.getOrNull(rating)?.trim()?.toIntOrNull()?.takeIf { it in 1..10 }
                )
            }
    }
}
