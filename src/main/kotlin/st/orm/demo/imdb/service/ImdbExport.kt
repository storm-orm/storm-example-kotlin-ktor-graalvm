package st.orm.demo.imdb.service

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One title of an IMDb export: its id, the day it was rated or added to the list, and the user's rating, if any. */
data class ImdbExportRow(val movieId: String, val date: LocalDate?, val rating: Int?)

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
    fun parse(text: String): List<ImdbExportRow> {
        val records = records(text.removePrefix("﻿"))
        val header = records.firstOrNull()?.map { it.trim() }.orEmpty()
        val id = header.indexOf(ID)
        require(id >= 0) { "Not an IMDb export: the file has no $ID column." }
        val dateRated = header.indexOf(DATE_RATED)
        val dateAdded = header.indexOf(DATE_ADDED)
        val rating = header.indexOf(RATING)
        return records.drop(1)
            .filter { it.getOrNull(id)?.startsWith("tt") == true }
            .map { record ->
                ImdbExportRow(
                    movieId = record[id].trim(),
                    date = date(record.getOrNull(dateRated)) ?: date(record.getOrNull(dateAdded)),
                    rating = record.getOrNull(rating)?.trim()?.toIntOrNull()?.takeIf { it in 1..10 }
                )
            }
    }

    private fun date(value: String?): LocalDate? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                null
            }
        }

    /**
     * The records of a CSV text (RFC 4180): fields separated by commas, a
     * field in double quotes may hold commas, line breaks and doubled quotes.
     */
    private fun records(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> field.append(c)
                c == ',' -> { record.add(field.toString()); field.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    record.add(field.toString()); field.clear()
                    if (record.any { it.isNotEmpty() }) records.add(record)
                    record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        record.add(field.toString())
        if (record.any { it.isNotEmpty() }) records.add(record)
        return records
    }
}
