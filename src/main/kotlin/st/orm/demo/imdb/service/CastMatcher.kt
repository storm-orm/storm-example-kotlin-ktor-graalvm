package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Credit
import java.text.Normalizer

/** What came of a name given: someone in the credits, someone named before, more than one, or no one. */
enum class NameVerdict { RIGHT, ALREADY, UNCLEAR, WRONG }

/** A name given, matched: the verdict, and the credit it named, for [NameVerdict.RIGHT] and [NameVerdict.ALREADY]. */
data class NameMatch(val verdict: NameVerdict, val credit: Credit? = null)

/**
 * Matches the names players type against a movie's credits, forgivingly:
 * whatever the case and the accents, with a typo or two in a long name, and
 * a surname alone when only one person in the credits has it. A name that
 * fits more than one is unclear, which costs the player nothing; they give
 * the full name.
 */
object CastMatcher {

    fun match(answer: String, credits: List<Credit>, named: Set<String>): NameMatch {
        val given = normalize(answer)
        if (given.isEmpty()) return NameMatch(NameVerdict.WRONG)
        val candidates = byFullName(given, credits).ifEmpty { bySurname(given, credits) }
        return when (candidates.size) {
            0 -> NameMatch(NameVerdict.WRONG)
            1 -> candidates.single().let { NameMatch(if (it.name in named) NameVerdict.ALREADY else NameVerdict.RIGHT, it) }
            else -> NameMatch(NameVerdict.UNCLEAR)
        }
    }

    /** The credits whose name is the one given, else the ones it is closest to within the typos its length allows. */
    private fun byFullName(given: String, credits: List<Credit>): List<Credit> {
        val distances = credits.associateWith { distance(given, normalize(it.name)) }
        val best = distances.values.minOrNull() ?: return emptyList()
        if (best > typosAllowed(given)) return emptyList()
        return distances.filterValues { it == best }.keys.toList()
    }

    /** The credits whose last name is the one word given, of three letters or more. */
    private fun bySurname(given: String, credits: List<Credit>): List<Credit> =
        if (' ' in given || given.length < 3) emptyList()
        else credits.filter { normalize(it.name).substringAfterLast(' ') == given }

    /** No typo in a short name, one from six letters, two from twelve. */
    private fun typosAllowed(name: String): Int = when {
        name.length >= 12 -> 2
        name.length >= 6 -> 1
        else -> 0
    }

    /** A name in lower case, without accents, its words separated by single spaces: "Penélope Cruz" is "penelope cruz". */
    fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** The edits from one text to the other: a letter added, left out, changed, or two swapped. */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
