package st.orm.demo.imdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.ClubMember
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.MovieListItemRepository
import st.orm.demo.imdb.repository.MovieListRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.service.ListService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class ListServiceTest {

    private fun service(orm: ORMTemplate) = ListService(
        orm.repository<MovieListRepository>(),
        orm.repository<MovieListItemRepository>(),
        orm.repository<ClubRepository>(),
        orm.repository<ClubMemberRepository>(),
        orm.repository<MovieRepository>()
    )

    @Test
    fun `a list of one's own is public, and only its owner changes it`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-lists", name = "Alice")
        val bob = orm.insertUser("bob-lists", name = "Bob")
        val list = runBlocking { service.create(alice, "  Mind benders ", " ", null, "tt0133093") }!!
        assertEquals("Mind benders", list.name)
        assertNull(list.description)

        assertEquals(true, runBlocking { service.toggle(alice, list.id, "tt0234215") })
        assertEquals(true, runBlocking { service.toggle(alice, list.id, "tt0211915") })
        assertEquals(false, runBlocking { service.toggle(alice, list.id, "tt0211915") })
        // Bob sees it, as anyone with the link does, but does not change it.
        val seenByBob = runBlocking { service.listOf(bob, list.id) }!!
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), seenByBob.items.map { it.movie.primaryTitle })
        assertFalse(seenByBob.canEdit)
        assertFalse(seenByBob.canManage)
        assertNull(runBlocking { service.toggle(bob, list.id, "tt0111161") })
        assertFalse(runBlocking { service.remove(bob, list.id) })

        // A few movies on it, and it is one of Bob's lists to discover, with its posters, the latest first.
        runBlocking { service.toggle(alice, list.id, "tt0111161") }
        val bobsPage = runBlocking { service.listsOf(bob) }
        assertEquals(listOf("Mind benders"), bobsPage.others.map { it.list.name })
        assertEquals(3L, bobsPage.others.single().movieCount)
        assertEquals("The Shawshank Redemption", bobsPage.others.single().posters.first().primaryTitle)
        assertTrue(runBlocking { service.listsOf(alice) }.others.isEmpty())

        val choices = runBlocking { service.choicesFor(alice, "tt0133093") }
        assertEquals(listOf(true), choices.map { it.contains })
        assertTrue(runBlocking { service.remove(alice, list.id) })
        assertNull(runBlocking { service.listOf(alice, list.id) })
    }

    @Test
    fun `a club's list is its members', who all add to it, and its admins manage it`(orm: ORMTemplate) {
        val service = service(orm)
        val alice = orm.insertUser("alice-club-lists", name = "Alice")
        val bob = orm.insertUser("bob-club-lists", name = "Bob")
        val carol = orm.insertUser("carol-club-lists", name = "Carol")
        val now = Instant.parse("2026-10-01T10:00:00Z")
        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(listOf(ClubMember(club, alice, admin = true, joinedAt = now), ClubMember(club, bob, admin = false, joinedAt = now)))

        // Carol is not in the club: she cannot start a list for it, nor see one.
        assertNull(runBlocking { service.create(carol, "Ours", null, club.id, null) })
        val list = runBlocking { service.create(bob, "Friday picks", "For the office", club.id, null) }!!
        assertNull(runBlocking { service.listOf(carol, list.id) })

        assertEquals(true, runBlocking { service.toggle(alice, list.id, "tt0110912") })
        val forAlice = runBlocking { service.listOf(alice, list.id) }!!
        assertTrue(forAlice.canEdit)
        assertTrue(forAlice.canManage)
        val forBob = runBlocking { service.listOf(bob, list.id) }!!
        assertTrue(forBob.canManage)
        assertEquals("Office", runBlocking { service.choicesFor(alice, "tt0110912") }.single().club)
        assertEquals(listOf("Friday picks"), runBlocking { service.listsOf(club) }.map { it.list.name })
        assertTrue(runBlocking { service.listsOf(carol) }.others.isEmpty())
        assertNotNull(runBlocking { service.listsOf(alice) }.clubLists.single())
    }
}
