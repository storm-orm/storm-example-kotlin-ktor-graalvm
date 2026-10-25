package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.WikidataSource
import st.orm.demo.imdb.model.WikidataSource_
import st.orm.repository.EntityRepository
import st.orm.template.inList
import st.orm.template.inRefs

/** The Wikidata sources: the rows of the kind 'wikidata', which only these queries see. */
interface WikidataSourceRepository : EntityRepository<WikidataSource, Long> {

    /** The sources of the movies with these Wikidata ids, for an import that names movies by them. */
    fun findByExternalIds(externalIds: Collection<String>): List<WikidataSource> =
        if (externalIds.isEmpty()) emptyList() else findAll(WikidataSource_.externalId inList externalIds)

    /** Stores what Wikidata answered about these movies, in place of what it answered before. */
    fun replace(sources: List<WikidataSource>) {
        removeAll(WikidataSource_.movie inRefs sources.map { it.movie })
        insert(sources)
    }
}
