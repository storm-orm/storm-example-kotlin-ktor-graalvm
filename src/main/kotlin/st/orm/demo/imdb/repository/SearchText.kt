package st.orm.demo.imdb.repository

import st.orm.Data
import st.orm.Metamodel
import st.orm.Navigable
import st.orm.SqlFunction
import st.orm.template.invoke

private val WHITESPACE = Regex("\\s+")

/**
 * The database's accent folding (V1): the text without its accents, through
 * PostgreSQL's unaccent. The searches apply it to the columns they match, and
 * Storm applies it to the input as well, so the database folds both sides and
 * "leon", "Léon" and "LÉON" all find Léon. The trigram indexes of V1
 * are built on LOWER(fold_accents(column)), the expression the searches
 * compare, so they serve them without a column that stores the folded text.
 */
internal val foldAccents = SqlFunction.of<String>("fold_accents")

/** The column without its accents, as the searches match it: `MovieSummary_.primaryTitle.folded`. */
internal val <T : Data> Navigable<T, String>.folded: Metamodel<T, String>
    get() = foldAccents(this)

/**
 * The search input as a title or name would read it, for the ranks that
 * compare it whole: its words one space apart.
 */
internal fun searchText(input: String): String =
    input.trim().replace(WHITESPACE, " ")
