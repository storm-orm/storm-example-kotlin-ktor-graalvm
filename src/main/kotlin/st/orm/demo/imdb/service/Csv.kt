package st.orm.demo.imdb.service

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The CSV files sites export a user's history in: IMDb's ([ImdbExport]) and TMDB's ([TmdbExport]). */
internal object Csv {

    /** The header and the records of a CSV text, a byte order mark ignored; the header is trimmed. */
    fun read(text: String): Pair<List<String>, List<List<String>>> {
        val records = records(text.removePrefix("﻿"))
        return records.firstOrNull()?.map { it.trim() }.orEmpty() to records.drop(1)
    }

    /** The day a field holds, written as a date or as a date and time, or null. */
    fun date(value: String?): LocalDate? =
        value?.trim()?.take(10)?.takeIf { it.length == 10 }?.let {
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
