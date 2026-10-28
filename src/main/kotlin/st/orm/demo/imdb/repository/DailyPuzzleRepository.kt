package st.orm.demo.imdb.repository

import st.orm.demo.imdb.model.DailyGame
import st.orm.demo.imdb.model.DailyPlay
import st.orm.demo.imdb.model.DailyPlayPk
import st.orm.demo.imdb.model.DailyPlay_
import st.orm.demo.imdb.model.DailyPuzzle
import st.orm.demo.imdb.model.DailyPuzzlePk
import st.orm.demo.imdb.model.User
import st.orm.repository.EntityRepository
import st.orm.template.and
import st.orm.template.eq
import st.orm.template.inList
import st.orm.template.isTrue
import java.time.LocalDate

interface DailyPuzzleRepository : EntityRepository<DailyPuzzle, DailyPuzzlePk>

interface DailyPlayRepository : EntityRepository<DailyPlay, DailyPlayPk> {

    /** Every daily the user played, finished or not. */
    fun findAll(user: User): List<DailyPlay> =
        findAll(DailyPlay_.user eq user)

    /** The games of a day several users played to the end, each with its player: the boards. */
    fun findFinished(users: List<User>, day: LocalDate, game: DailyGame): List<DailyPlay> =
        if (users.isEmpty()) emptyList()
        else findAll((DailyPlay_.user inList users) and (DailyPlay_.id.puzzleOn eq day) and (DailyPlay_.id.game eq game) and DailyPlay_.finished.isTrue())
}
