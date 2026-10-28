package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref

data class MovieNightProviderPk(
    val nightId: Long,
    val watchProviderId: Int
)

/** A streaming service a movie night is watched on, keyed by the pair; the service comes with the row, for its logo. */
data class MovieNightProvider(
    @PK(generation = NONE) val id: MovieNightProviderPk,
    @FK @Persist(insertable = false, updatable = false) val night: Ref<MovieNight>,
    @FK @Persist(insertable = false, updatable = false) val watchProvider: WatchProvider
) : Entity<MovieNightProviderPk> {
    constructor(night: MovieNight, watchProvider: WatchProvider) : this(
        id = MovieNightProviderPk(nightId = night.id, watchProviderId = watchProvider.id),
        night = night.ref(),
        watchProvider = watchProvider
    )
}
