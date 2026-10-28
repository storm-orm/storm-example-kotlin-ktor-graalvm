package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.DraftTheme
import st.orm.demo.imdb.model.GameMatch
import st.orm.repository.EntityRepository

interface DraftThemeRepository : EntityRepository<DraftTheme, GameMatch>
