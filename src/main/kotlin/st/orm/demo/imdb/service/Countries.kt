package st.orm.demo.imdb.service

import java.util.Locale

/** A country, by its ISO 3166-1 alpha-2 code, with its English name. */
data class Country(val code: String, val name: String)

/**
 * The countries a user can be from, for where movies play: every country
 * Java knows by code, in the order of their names. TMDB names the platforms
 * by the same codes.
 */
object Countries {
    val all: List<Country> = Locale.getISOCountries()
        .map { Country(it, Locale.of("", it).getDisplayCountry(Locale.ENGLISH)) }
        .sortedBy { it.name }

    private val byCode: Map<String, Country> = all.associateBy { it.code }

    /** The country with this code, or null for a code that names none. */
    fun of(code: String?): Country? = code?.let { byCode[it.uppercase()] }
}
