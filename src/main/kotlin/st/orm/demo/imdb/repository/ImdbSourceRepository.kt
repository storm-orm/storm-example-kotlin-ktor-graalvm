package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.ImdbSource
import st.orm.demo.imdb.model.ImdbSource_
import st.orm.repository.EntityRepository
import st.orm.template.inList

/** The IMDb sources: the rows of the kind 'imdb', which only these queries see. */
interface ImdbSourceRepository : EntityRepository<ImdbSource, Long> {

    /** The sources of the movies with these IMDb ids, for an import that names movies by them. */
    fun findByExternalIds(externalIds: Collection<String>): List<ImdbSource> =
        if (externalIds.isEmpty()) emptyList() else findAll(ImdbSource_.externalId inList externalIds)
}
