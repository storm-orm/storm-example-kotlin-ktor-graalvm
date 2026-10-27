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
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.Principal
import st.orm.demo.imdb.repository.ChainLinkRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GameCardRepository
import st.orm.demo.imdb.repository.GameMatchRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.PersonRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.RatingRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.demo.imdb.service.ChainMove
import st.orm.demo.imdb.service.ChainService
import st.orm.demo.imdb.service.ConnectMove
import st.orm.demo.imdb.service.ConnectService
import st.orm.demo.imdb.service.DraftMove
import st.orm.demo.imdb.service.DraftService
import st.orm.demo.imdb.service.HigherLowerService
import st.orm.demo.imdb.service.MatchService
import st.orm.demo.imdb.service.Opponents
import st.orm.demo.imdb.service.PlayService
import st.orm.demo.imdb.service.QuizService
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.template.ref
import st.orm.test.StormTest
import java.time.Duration
import java.time.Instant

@StormTest(scripts = ["/schema.sql", "/data.sql"])
class GamesTest {

    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun matches(orm: ORMTemplate) = MatchService(
        orm.repository<GameMatchRepository>(), orm.repository<GamePlayerRepository>(), orm.repository<FriendshipRepository>(),
        orm.repository<ClubRepository>(), orm.repository<ClubMemberRepository>(), orm.repository<UserRepository>()
    )

    private fun higherLower(orm: ORMTemplate) = HigherLowerService(
        matches(orm), orm.repository<GamePlayerRepository>(), orm.repository<GameCardRepository>(),
        orm.repository<RatingRepository>(), orm.repository<ClubMemberRepository>(), orm.repository<UserRepository>()
    )

    private fun chains(orm: ORMTemplate) = ChainService(
        matches(orm), orm.repository<GameMatchRepository>(), orm.repository<GamePlayerRepository>(), orm.repository<ChainLinkRepository>(),
        orm.repository<PrincipalRepository>(), orm.repository<RatingRepository>(), orm.repository<MovieRepository>(),
        orm.repository<PersonRepository>(), orm.repository<UserRepository>()
    )

    @Test
    fun `higher or lower goes on with every right call and ends at the first wrong one`(orm: ORMTemplate) {
        val service = higherLower(orm)
        val alice = orm.insertUser("alice-hl", name = "Alice")
        val match = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        var game = runBlocking { service.matchOf(alice, match.id, now) }!!
        // The fixture's six movies, no two in a row with the same rating.
        assertEquals(6, game.cardCount)
        assertTrue(game.playing)

        repeat(2) {
            val higher = game.next!!.rating > game.current!!.rating
            runBlocking { service.answer(alice, match.id, higher, now) }
            game = runBlocking { service.matchOf(alice, match.id, now) }!!
        }
        assertEquals(2, game.streak)
        val wrong = game.next!!.rating < game.current!!.rating
        runBlocking { service.answer(alice, match.id, wrong, now) }
        game = runBlocking { service.matchOf(alice, match.id, now) }!!
        assertTrue(game.finished)
        assertFalse(game.playing)
        assertEquals(2, game.streak)
        assertNotNull(game.missed)
        assertEquals(2, game.best)
        // Nobody else sees a game on one's own.
        assertNull(runBlocking { service.matchOf(orm.insertUser("bob-hl", name = "Bob"), match.id, now) })
    }

    @Test
    fun `a friend's challenge is for friends only, and a club's round is joined by playing it until it closes`(orm: ORMTemplate) {
        val service = higherLower(orm)
        val alice = orm.insertUser("alice-round", name = "Alice")
        val bob = orm.insertUser("bob-round", name = "Bob")
        val carol = orm.insertUser("carol-round", name = "Carol")
        assertNull(runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) })
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val challenge = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        // A round deals up to 25 movies: the fixture's six here.
        assertEquals(6, orm.repository<GameCardRepository>().findCards(challenge).size)
        assertEquals(listOf("Alice", "Bob"), runBlocking { service.matchOf(bob, challenge.id, now) }!!.players.mapNotNull { it.user.name }.sorted())

        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(listOf(ClubMember(club, alice, true, now), ClubMember(club, carol, false, now)))
        val round = runBlocking { service.start(alice, Opponents(GameMode.CLUB, clubId = club.id), now) }!!
        assertEquals(1, runBlocking { service.matchOf(alice, round.id, now) }!!.players.size)
        runBlocking { service.answer(carol, round.id, true, now) }
        assertEquals(2, runBlocking { service.matchOf(carol, round.id, now) }!!.players.size)
        // Bob is no member: he neither sees nor joins it; and after two days the round is closed.
        assertNull(runBlocking { service.matchOf(bob, round.id, now) })
        val later = now.plus(Duration.ofHours(49))
        assertTrue(runBlocking { service.matchOf(alice, round.id, later) }!!.over)
    }

    /** Morgan Freeman in The Matrix Reloaded: its third actor, so the chain starts there, the one movie with a cast of three. */
    private fun castOfThree(orm: ORMTemplate) {
        orm.repository<PrincipalRepository>().insert(
            Principal(orm.repository<MovieRepository>().getById("tt0234215").ref(), 3, orm.repository<PersonRepository>().getById("nm0000151"), "actor", "Somebody")
        )
    }

    @Test
    fun `friends chain movies through shared actors in turn, and a turn not taken passes on`(orm: ORMTemplate) {
        castOfThree(orm)
        val service = chains(orm)
        val alice = orm.insertUser("alice-chain", name = "Alice")
        val bob = orm.insertUser("bob-chain", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val match = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        var chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertEquals("The Matrix Reloaded", chain.last.movie.primaryTitle)
        // Alice, the host, plays first.
        assertTrue(chain.yourTurn)
        // The players see the movies only, until one takes a hint.
        assertTrue(chain.revealed.isEmpty())

        assertEquals(ChainMove.NOT_YOUR_TURN, runBlocking { service.move(bob, match.id, "tt0133093", now) })
        assertEquals(ChainMove.NO_SHARED_ACTOR, runBlocking { service.move(alice, match.id, "tt0110912", now) })
        assertEquals(ChainMove.ADDED, runBlocking { service.move(alice, match.id, "tt0133093", now) })
        assertEquals(ChainMove.ALREADY_IN, runBlocking { service.move(bob, match.id, "tt0234215", now.plusSeconds(60)) })
        chain = runBlocking { service.chainOf(alice, match.id, now.plusSeconds(120)) }!!
        assertEquals("Keanu Reeves", chain.last.via!!.primaryName)
        // The Matrix Reloaded (2003) to The Matrix (1999): four years.
        assertEquals(mapOf("Alice" to 4, "Bob" to 0), chain.players.associate { it.user.name to it.score })
        assertFalse(chain.yourTurn)

        // Bob lets a day go by: the turn has passed back to Alice.
        val dayLater = now.plus(Duration.ofHours(25))
        chain = runBlocking { service.chainOf(alice, match.id, dayLater) }!!
        assertTrue(chain.yourTurn)
        assertEquals(1, chain.turn!!.skipped)
        // Alice is stuck: the chain is over, and she won.
        runBlocking { service.giveUp(alice, match.id, dayLater) }
        chain = runBlocking { service.chainOf(alice, match.id, dayLater) }!!
        assertTrue(chain.over)
        assertEquals("Alice", chain.standings.first().user.name)
    }

    @Test
    fun `on one's own the house answers every movie, and loses when it finds none`(orm: ORMTemplate) {
        castOfThree(orm)
        // Keanu Reeves in Pulp Fiction too, for the house to answer The Matrix with.
        orm.repository<PrincipalRepository>().insert(
            Principal(orm.repository<MovieRepository>().getById("tt0110912").ref(), 2, orm.repository<PersonRepository>().getById("nm0000206"), "actor", "Somebody")
        )
        val service = chains(orm)
        val alice = orm.insertUser("alice-house", name = "Alice")
        val match = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        assertEquals(ChainMove.ADDED, runBlocking { service.move(alice, match.id, "tt0133093", now) })
        var chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertEquals("Pulp Fiction", chain.last.movie.primaryTitle)
        assertNull(chain.last.player)
        assertTrue(chain.yourTurn)
        assertFalse(chain.over)
        // Nothing shares an actor with Pulp Fiction but the movies in the chain: Alice is stuck, and the house wins.
        runBlocking { service.giveUp(alice, match.id, now) }
        chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertTrue(chain.over)
        // The house went through Keanu Reeves again, as Alice did: an escape, without points.
        assertEquals(0, chain.housePoints)

        // The Matrix Reloaded (2003) to The Matrix (1999): four years, four points; over, so among the finished games.
        val play = runBlocking { plays(orm).playOf(alice, now) }
        assertTrue(play.games.isEmpty())
        assertEquals(listOf("Over: 4 points"), play.finished.map { it.status })
    }

    private fun plays(orm: ORMTemplate) = PlayService(
        matches(orm), orm.repository(), orm.repository(), chains(orm), drafts(orm), connects(orm), orm.repository(),
        QuizService(
            orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
            orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository()
        )
    )

    @Test
    fun `a game closed on one's own or with a friend ends, and in a club the others play on`(orm: ORMTemplate) {
        val service = higherLower(orm)
        val play = plays(orm)
        val alice = orm.insertUser("alice-close", name = "Alice")
        val bob = orm.insertUser("bob-close", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val solo = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        val challenge = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        val club = orm.repository<ClubRepository>().insertAndFetch(Club(name = "Office", createdAt = now))
        orm.repository<ClubMemberRepository>().insert(listOf(ClubMember(club, alice, true, now), ClubMember(club, bob, false, now)))
        val round = runBlocking { service.start(alice, Opponents(GameMode.CLUB, clubId = club.id), now) }!!
        runBlocking { service.answer(bob, round.id, true, now) }
        assertEquals(3, runBlocking { play.playOf(alice, now) }.games.size)

        listOf(solo, challenge, round).forEach { runBlocking { play.close(alice, it.id, now) } }
        val after = runBlocking { play.playOf(alice, now) }
        assertTrue(after.games.isEmpty())
        assertEquals(3, runBlocking { play.archiveOf(alice, 0, now) }.games.size)
        // The friend's game ended for Bob too; the club's round goes on for him.
        assertTrue(runBlocking { service.matchOf(bob, challenge.id, now) }!!.over)
        assertFalse(runBlocking { service.matchOf(bob, round.id, now) }!!.over)
        assertEquals(listOf(round.id), runBlocking { play.playOf(bob, now) }.games.map { it.match.id })
    }

    @Test
    fun `at the most open games a new one closes the oldest on one's own, and waits when all are with others`(orm: ORMTemplate) {
        val service = higherLower(orm)
        val play = plays(orm)
        val alice = orm.insertUser("alice-room", name = "Alice")
        val bob = orm.insertUser("bob-room", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val first = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        repeat(PlayService.MAX_OPEN - 1) { i -> runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now.plusSeconds(i + 1L)) } }
        assertTrue(runBlocking { play.makeRoom(alice, now) })
        // The game on her own made room, and is in the archive.
        assertEquals(listOf(first.id), runBlocking { play.archiveOf(alice, 0, now) }.games.map { it.match.id })
        runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now.plusSeconds(60)) }
        assertFalse(runBlocking { play.makeRoom(alice, now) })
    }

    @Test
    fun `a hint shows one actor of the last movie at a time, three at most, and once over the chain shows where it could have gone`(orm: ORMTemplate) {
        castOfThree(orm)
        orm.repository<PrincipalRepository>().insert(
            Principal(orm.repository<MovieRepository>().getById("tt0110912").ref(), 2, orm.repository<PersonRepository>().getById("nm0000206"), "actor", "Somebody")
        )
        val service = chains(orm)
        val alice = orm.insertUser("alice-hints", name = "Alice")
        val match = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        assertTrue(runBlocking { service.chainOf(alice, match.id, now) }!!.revealed.isEmpty())
        repeat(4) { runBlocking { service.hint(alice, match.id, now) } }
        var chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne", "Morgan Freeman"), chain.revealed.map { it.primaryName })
        assertFalse(chain.moreHints)
        assertEquals(3, chain.players.single().hints)

        runBlocking { service.giveUp(alice, match.id, now) }
        chain = runBlocking { service.chainOf(alice, match.id, now) }!!
        assertEquals(listOf("The Shawshank Redemption" to "Morgan Freeman", "Pulp Fiction" to "Keanu Reeves", "The Matrix" to "Keanu Reeves"),
            chain.answers.map { it.movie.primaryTitle to it.actor.primaryName })
    }

    private fun connects(orm: ORMTemplate) = ConnectService(
        matches(orm), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
        orm.repository(), orm.repository(), orm.repository(), orm.repository(), parRange = 2..4
    )

    private fun drafts(orm: ORMTemplate) = DraftService(
        matches(orm), orm.repository(), orm.repository(), orm.repository(), orm.repository(), orm.repository(),
        orm.repository(), orm.repository(), orm.repository(), rounds = 1, margin = 1
    )

    @Test
    fun `a draft on one's own has the house pick after the player, and the best total wins`(orm: ORMTemplate) {
        val service = drafts(orm)
        val alice = orm.insertUser("alice-draft", name = "Alice")
        val match = runBlocking { service.start(alice, Opponents(GameMode.SOLO), now) }!!
        var draft = runBlocking { service.draftOf(alice, match.id, now) }!!
        // The one theme of the fixture with a movie for each drafter: Shawshank and Pulp Fiction.
        assertEquals("Drama of the 1990s", draft.theme.label)
        assertTrue(draft.yourTurn)
        assertEquals(DraftMove.OFF_THEME, runBlocking { service.pick(alice, match.id, "tt0133093", now) })
        assertEquals(DraftMove.PICKED, runBlocking { service.pick(alice, match.id, "tt0110912", now) })
        draft = runBlocking { service.draftOf(alice, match.id, now) }!!
        assertTrue(draft.over)
        assertEquals(listOf("The house" to "9.3", "Alice" to "8.9"), draft.standings.map { it.drafter.name to it.total.toString() })
    }

    @Test
    fun `a draft with a friend picks for a player who lets their turn go by`(orm: ORMTemplate) {
        val service = drafts(orm)
        val alice = orm.insertUser("alice-draft-friend", name = "Alice")
        val bob = orm.insertUser("bob-draft-friend", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val match = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        assertEquals(DraftMove.NOT_YOUR_TURN, runBlocking { service.pick(bob, match.id, "tt0110912", now) })
        // A day later Alice's pick was made for her, and it is Bob's turn.
        val later = now.plus(Duration.ofHours(25))
        var draft = runBlocking { service.draftOf(bob, match.id, later) }!!
        assertTrue(draft.picks.single().auto)
        assertTrue(draft.yourTurn)
        val left = draft.board.single()
        assertEquals(DraftMove.PICKED, runBlocking { service.pick(bob, match.id, left.id, later) })
        draft = runBlocking { service.draftOf(alice, match.id, later) }!!
        assertTrue(draft.over)
        assertEquals(2, draft.lineUps.size)
    }

    /** A friend chain from The Matrix Reloaded, Alice through The Matrix, by Keanu Reeves, the best billed both share. */
    private fun chainToTheMatrix(orm: ORMTemplate, vararg pulpFictionCast: Pair<String, Int>): Pair<ChainService, Long> {
        castOfThree(orm)
        val service = chains(orm)
        val alice = orm.insertUser("alice-escape", name = "Alice")
        val bob = orm.insertUser("bob-escape", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val match = runBlocking { service.start(alice, Opponents(GameMode.FRIEND, friendId = bob.id), now) }!!
        // Pulp Fiction's cast added once the chain started, so it does not start there.
        val pulp = orm.repository<MovieRepository>().getById("tt0110912").ref()
        pulpFictionCast.forEach { (person, ordering) ->
            orm.repository<PrincipalRepository>().insert(Principal(pulp, ordering, orm.repository<PersonRepository>().getById(person), "actor", "Somebody"))
        }
        assertEquals(ChainMove.ADDED, runBlocking { service.move(alice, match.id, "tt0133093", now) })
        assertEquals(ChainMove.ADDED, runBlocking { service.move(bob, match.id, "tt0110912", now) })
        return service to match.id
    }

    @Test
    fun `a link through the same actor as the link before is an escape, without points`(orm: ORMTemplate) {
        // Keanu Reeves is all Pulp Fiction shares with The Matrix.
        val (service, matchId) = chainToTheMatrix(orm, "nm0000206" to 2)
        val chain = runBlocking { service.chainOf(orm.repository<UserRepository>().getById("alice-escape"), matchId, now) }!!
        assertEquals(listOf("Keanu Reeves", "Keanu Reeves"), chain.links.drop(1).map { it.via!!.primaryName })
        // The Matrix Reloaded (2003) to The Matrix (1999) scores its four years; the escape nothing.
        assertEquals(listOf(4, 0), chain.links.drop(1).map { it.points })
        assertEquals(mapOf("Alice" to 4, "Bob" to 0), chain.players.associate { it.user.name to it.score })
    }

    @Test
    fun `a link is an escape whenever the actor of the link before is in the next movie too`(orm: ORMTemplate) {
        // Pulp Fiction has Keanu Reeves and Laurence Fishburne: Keanu Reeves, the actor of the link before, is in it.
        val (service, matchId) = chainToTheMatrix(orm, "nm0000206" to 2, "nm0000401" to 3)
        val chain = runBlocking { service.chainOf(orm.repository<UserRepository>().getById("bob-escape"), matchId, now) }!!
        assertEquals("Keanu Reeves", chain.last.via!!.primaryName)
        assertEquals(0, chain.last.points)
    }

    @Test
    fun `a link through another actor scores`(orm: ORMTemplate) {
        // Pulp Fiction has Laurence Fishburne, but not Keanu Reeves, the actor of the link before.
        val (service, matchId) = chainToTheMatrix(orm, "nm0000401" to 3)
        val chain = runBlocking { service.chainOf(orm.repository<UserRepository>().getById("bob-escape"), matchId, now) }!!
        assertEquals("Laurence Fishburne", chain.last.via!!.primaryName)
        // The Matrix (1999) to Pulp Fiction (1994): five years.
        assertEquals(5, chain.last.points)
    }

    @Test
    fun `connect finds par from both ends, and a team joins the two movies, any player any time`(orm: ORMTemplate) {
        castOfThree(orm)
        val service = connects(orm)
        val alice = orm.insertUser("alice-connect", name = "Alice")
        val bob = orm.insertUser("bob-connect", name = "Bob")
        orm.repository<FriendshipRepository>().befriend(alice, bob, now)
        val movies = orm.repository<MovieRepository>()
        // The Matrix to The Shawshank Redemption: through Keanu Reeves to The Matrix Reloaded, then Morgan Freeman.
        val match = runBlocking { service.startWith(alice, Opponents(GameMode.FRIEND, friendId = bob.id), movies.getById("tt0133093"), movies.getById("tt0111161"), now) }!!
        // Two movies with no way between them make no puzzle.
        assertNull(runBlocking { service.startWith(alice, Opponents(GameMode.SOLO), movies.getById("tt0133093"), movies.getById("tt0068646"), now) })

        var puzzle = runBlocking { service.puzzleOf(bob, match.id) }!!
        assertEquals(2, puzzle.puzzle.par)
        assertEquals(listOf("tt0133093", "tt0234215", "tt0111161"), puzzle.puzzle.path.movies)
        assertEquals(listOf("nm0000206", "nm0000151"), puzzle.puzzle.path.actors)

        runBlocking { service.hint(bob, match.id, now) }
        puzzle = runBlocking { service.puzzleOf(alice, match.id) }!!
        assertEquals(listOf("Keanu Reeves"), puzzle.revealed.map { it.primaryName })
        assertEquals(ConnectMove.NO_SHARED_ACTOR, runBlocking { service.move(bob, match.id, "tt0110912", now) })
        // Bob, without waiting for a turn, adds The Matrix Reloaded: it shares Morgan Freeman with the target, which closes the chain.
        assertEquals(ConnectMove.CONNECTED, runBlocking { service.move(bob, match.id, "tt0234215", now) })
        puzzle = runBlocking { service.puzzleOf(alice, match.id) }!!
        assertTrue(puzzle.connected)
        assertEquals(2, puzzle.linkCount)
        assertEquals(0, puzzle.overPar)
        assertEquals("Morgan Freeman", puzzle.last.via!!.primaryName)
        assertEquals(listOf("The Matrix", "The Matrix Reloaded", "The Shawshank Redemption"), puzzle.parWay.map { it.movie.primaryTitle })
        assertEquals(ConnectMove.OVER, runBlocking { service.move(alice, match.id, "tt0110912", now) })
    }

    @Test
    fun `a link can be taken back to try another way, counted among the undos`(orm: ORMTemplate) {
        castOfThree(orm)
        // Keanu Reeves in Pulp Fiction too: a way from The Matrix that leads nowhere.
        orm.repository<PrincipalRepository>().insert(
            Principal(orm.repository<MovieRepository>().getById("tt0110912").ref(), 2, orm.repository<PersonRepository>().getById("nm0000206"), "actor", "Somebody")
        )
        val service = connects(orm)
        val alice = orm.insertUser("alice-undo", name = "Alice")
        val movies = orm.repository<MovieRepository>()
        val match = runBlocking { service.startWith(alice, Opponents(GameMode.SOLO), movies.getById("tt0133093"), movies.getById("tt0111161"), now) }!!
        assertEquals(ConnectMove.ADDED, runBlocking { service.move(alice, match.id, "tt0110912", now) })
        runBlocking { service.undo(alice, match.id, now) }
        var puzzle = runBlocking { service.puzzleOf(alice, match.id) }!!
        assertEquals("The Matrix", puzzle.last.movie.primaryTitle)
        assertEquals(1, puzzle.puzzle.undos)
        // Undoing the start does nothing.
        runBlocking { service.undo(alice, match.id, now) }
        assertEquals(1, runBlocking { service.puzzleOf(alice, match.id) }!!.links.size)
        runBlocking { service.giveUp(alice, match.id, now) }
        puzzle = runBlocking { service.puzzleOf(alice, match.id) }!!
        assertTrue(puzzle.over)
        assertFalse(puzzle.connected)
        assertEquals(3, puzzle.parWay.size)
    }
}
