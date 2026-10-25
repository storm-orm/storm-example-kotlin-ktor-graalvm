package st.orm.demo.imdb.model

/**
 * A kind of cinema a movie belongs to, read from what Wikidata says of it:
 * where it was made and in what language ([MovieTag]). A movie may have
 * several tags, or none, which is everything else; a tag is added by adding
 * it here with the rule that gives it (WikidataService). Stored by name.
 */
enum class Tag(val label: String) {
    /** Made in the United States. */
    HOLLYWOOD("Hollywood"),
    /** Made in India, in Hindi. */
    BOLLYWOOD("Bollywood")
}
