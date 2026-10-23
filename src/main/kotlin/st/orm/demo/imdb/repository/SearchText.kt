package st.orm.demo.imdb.repository

import st.orm.Data
import st.orm.Metamodel
import st.orm.Navigable
import st.orm.SqlFunction
import st.orm.template.invoke

/**
 * The database's accent folding (V1): the text without its accents, through
 * PostgreSQL's unaccent. The searches apply it to the names they match, and
 * Storm applies it to the input as well, so the database folds both sides and
 * "penelope" finds Penélope Cruz. The trigram index of V1 on the names is built on
 * LOWER(fold_accents(primary_name)), the expression the searches compare. The
 * titles are folded once, when they are written, and stored in the movie's
 * query columns (V2): a common word matches tens of thousands of titles, too
 * many to fold on every keystroke. Those columns name the function themselves
 * (see Movie), so the title searches fold the input and not the column.
 */
internal val foldAccents = SqlFunction.of<String>("fold_accents")

/** The column without its accents, as the searches match it: `Person_.primaryName.folded`. */
internal val <T : Data> Navigable<T, String>.folded: Metamodel<T, String>
    get() = foldAccents(this)
