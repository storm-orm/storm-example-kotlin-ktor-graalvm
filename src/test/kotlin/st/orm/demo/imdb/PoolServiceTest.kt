package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.model.MovieSeen
import st.orm.demo.imdb.model.Recommendation
import st.orm.demo.imdb.model.RecommendationPk
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.service.PoolKind
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.StormTest
import java.math.BigDecimal
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class PoolServiceTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    @Test
    fun `a pool is what the players watched, all of them or any, and says how many each knew`(orm: ORMTemplate) {
        val movies = orm.repository<MovieRepository>().findAll().sortedBy { it.id }
        val alice = orm.insertUser("alice-pool", name = "Alice")
        val bob = orm.insertUser("bob-pool", name = "Bob")
        orm.repository<MovieSeenRepository>().insert(movies.take(4).map { MovieSeen(alice, it, now) } + movies.drop(2).map { MovieSeen(bob, it, now) })
        val pools = poolService(orm)

        val shared = pools.poolOf(alice, listOf(alice, bob), PoolKind.INTERSECTION, 1, 100, minimum = 1)
        assertEquals(movies.subList(2, 4).map { it.id }.toSet(), shared.movies.map { it.id }.toSet())
        assertFalse(shared.fellBack)
        assertEquals(mapOf(alice.id to 2, bob.id to 2), shared.seenCounts)

        val any = pools.poolOf(alice, listOf(alice, bob), PoolKind.UNION, 1, 100, minimum = 1)
        assertEquals(6, any.size)
        assertEquals(mapOf(alice.id to 4, bob.id to 4), any.seenCounts)
        // The best known first: The Shawshank Redemption.
        assertEquals("tt0111161", any.movies.first().id)

        val unseen = pools.poolOf(alice, listOf(alice), PoolKind.UNSEEN, 1, 100, minimum = 1)
        assertEquals(movies.drop(4).map { it.id }.toSet(), unseen.movies.map { it.id }.toSet())
    }

    @Test
    fun `too few in common and the house falls back on well-known movies, saying so`(orm: ORMTemplate) {
        val alice = orm.insertUser("alice-few", name = "Alice")
        val bob = orm.insertUser("bob-few", name = "Bob")
        val pool = poolService(orm).poolOf(alice, listOf(alice, bob), PoolKind.INTERSECTION, 1, 100)
        assertTrue(pool.fellBack)
        assertEquals(6, pool.size)
        assertEquals(mapOf(alice.id to 0, bob.id to 0), pool.seenCounts)
    }

    @Test
    fun `the recommended pool is what the site picked for the players, the ones picked for most first, none of them watched`(orm: ORMTemplate) {
        val movies = orm.repository<MovieRepository>().findAll().sortedBy { it.id }
        val alice = orm.insertUser("alice-rec", name = "Alice")
        val bob = orm.insertUser("bob-rec", name = "Bob")
        fun pick(user: st.orm.demo.imdb.model.User, movie: st.orm.demo.imdb.model.Movie, score: String) =
            Recommendation(RecommendationPk(userId = user.id, movieId = movie.id), user.ref(), movie, BigDecimal(score), null, emptyList(), now)
        orm.repository<RecommendationRepository>().insert(listOf(
            pick(alice, movies[0], "0.9"), pick(alice, movies[1], "0.5"), pick(alice, movies[2], "0.8"),
            pick(bob, movies[1], "0.7"), pick(bob, movies[3], "0.6")
        ))
        // Alice watched the one both were recommended... no: she watched movies[2], which goes.
        orm.repository<MovieSeenRepository>().insert(MovieSeen(alice, movies[2], now))
        val pool = poolService(orm).poolOf(alice, listOf(alice, bob), PoolKind.RECOMMENDED, 1, 100, minimum = 1)
        assertEquals(listOf(movies[1].id, movies[0].id, movies[3].id), pool.movies.map { it.id })
        assertFalse(pool.fellBack)
    }
}
