package st.orm.demo.imdb.service

import java.util.Locale

/** The languages movies are made in, by their ISO 639 codes, as Wikidata gives them (MovieLanguage). */
object Languages {

    /** The English name of a language: "Hindi" for "hi"; the code itself for one Java has no name for. */
    fun nameOf(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
}
