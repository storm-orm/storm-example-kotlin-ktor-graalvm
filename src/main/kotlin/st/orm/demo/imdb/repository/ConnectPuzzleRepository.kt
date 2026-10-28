package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.ConnectPuzzle
import st.orm.demo.imdb.model.GameMatch
import st.orm.repository.EntityRepository

interface ConnectPuzzleRepository : EntityRepository<ConnectPuzzle, GameMatch>
