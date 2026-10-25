package st.orm.demo.imdb.repository

import kotlinx.serialization.Serializable
import st.orm.GenerateMetamodel
import st.orm.demo.imdb.model.RecommendationEvent
import st.orm.demo.imdb.model.RecommendationEventKind
import st.orm.demo.imdb.model.RecommendationEvent_
import st.orm.repository.EntityRepository
import st.orm.repository.select

/**
 * Query result shape: how many times recommendations led to one kind of
 * event, for the statistics page. Not backed by a database table or view,
 * so it is a plain data class — deliberately not a Data type.
 */
@Serializable
@GenerateMetamodel
data class RecommendationEventCount(
    val kind: RecommendationEventKind,
    val count: Long
)

interface RecommendationEventRepository : EntityRepository<RecommendationEvent, Long> {

    /** The events per kind, in the order of the kinds. */
    fun countByKind(): List<RecommendationEventCount> =
        select<RecommendationEventCount, _, _> { "${RecommendationEvent_.kind}, COUNT(*)" }
            .groupBy(RecommendationEvent_.kind)
            .resultList
            .sortedBy { it.kind }
}
