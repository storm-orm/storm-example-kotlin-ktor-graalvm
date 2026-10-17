package st.orm.demo.imdb

import java.text.Normalizer

/** Functions the H2 test schema declares, standing in for PostgreSQL's. */
object H2Functions {

    private val COMBINING_MARKS = Regex("\\p{M}+")

    /** PostgreSQL's fold_accents (V1) over unaccent, for the Latin accents the tests use. */
    @JvmStatic
    fun foldAccents(text: String?): String? =
        text?.let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(COMBINING_MARKS, "") }
}
