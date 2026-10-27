package st.orm.demo.imdb.service

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import st.orm.demo.imdb.model.GameMode
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.NightMethod
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.FriendshipRepository
import st.orm.demo.imdb.repository.GamePlayerRepository
import st.orm.demo.imdb.repository.MovieNightRepository
import st.orm.demo.imdb.repository.MovieNightVoteRepository
import st.orm.demo.imdb.repository.ACTING_CATEGORIES
import st.orm.demo.imdb.repository.DIRECTOR
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.PrincipalRepository
import st.orm.demo.imdb.repository.TopTenRepository
import st.orm.demo.imdb.repository.UserRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction

/** A night of one of the visitor's clubs still to decide, as the community page lists it. */
data class NightCard(val night: MovieNight, val answered: Boolean) {
    val day: String get() = night.nightOn.format(NIGHT_DAY)
    val state: String get() = if (night.method == NightMethod.SWIPE) "Swipe the deck" else "The vote is open"

    private companion object {
        val NIGHT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    }
}

/**
 * The community page: the visitor's friends and clubs together, and what
 * goes on among them. What they watched lately, check-ins first and their
 * latest watches when nobody checked in; the games the visitor plays with
 * them and the nights to answer, to pick up from here; and the people and
 * clubs themselves, each a way to their own page. The friends and clubs
 * with their invites and sharing are what the page manages.
 */
data class CommunityView(
    val friends: FriendsView,
    val clubs: ClubsView,
    /** What friends and club members watched lately: check-ins, or their latest watches. */
    val feed: List<SharedCheckIn>,
    val feedIsCheckIns: Boolean,
    /** The visitor's open games with friends and clubs, the ones waiting on them first. */
    val games: List<GameEntry>,
    /** The nights of the visitor's clubs still to decide. */
    val nights: List<NightCard>,
    /** Everyone in the community, with what the visitor has in common with each, the most first. */
    val people: List<InCommon>,
    /** What the keyboard behind the title types: titles and names from the visitor's own movies. */
    val typed: List<String>
) {
    val keyboard: List<List<String>> get() = CommunityService.KEYBOARD
    /** Whether the visitor has friends or clubs: without, the page is what manages them. */
    val hasCommunity: Boolean get() = friends.friends.isNotEmpty() || clubs.clubs.isNotEmpty()
    /** What waits on the visitor here: requests, nights and games. */
    val waiting: Int get() = friends.requests.size + nights.count { !it.answered } + games.count { it.yourMove }
}

/**
 * A friend's own page: what the two have in common, what the friend
 * watched lately, and the games the two play, with the ways to start one.
 */
data class FriendView(
    val friend: User,
    val inCommon: InCommon?,
    val moviesInCommon: List<Movie>,
    val feed: List<SharedCheckIn>,
    val games: List<GameEntry>,
    val typed: List<String>,
    /** The friend's top ten, as far as they share what they watch, and their honourable mentions. */
    val topTen: List<Movie> = emptyList(),
    val mentions: List<Movie> = emptyList()
) {
    val keyboard: List<List<String>> get() = CommunityService.KEYBOARD
    /** The matches the friend can be challenged to. */
    val challenges: List<GameRules.Challenge> get() = GameRules.challenges
}

/**
 * The visitor's community as one: friends and the members of their clubs.
 * A person who is both is one person, and shares what they watch as a
 * friend does, or else as a member of any club both are in.
 */
class CommunityService(
    private val friendService: FriendService,
    private val clubService: ClubService,
    private val tasteOverlap: TasteOverlap,
    private val userRepository: UserRepository,
    private val friendshipRepository: FriendshipRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val movieNightRepository: MovieNightRepository,
    private val movieNightVoteRepository: MovieNightVoteRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val playService: PlayService,
    private val principalRepository: PrincipalRepository,
    private val topTenRepository: TopTenRepository
) {
    suspend fun overview(user: User, now: Instant = Instant.now()): CommunityView {
        // The friends, the clubs and the games, each its own transaction, read side by side.
        val (friends, clubs, games) = coroutineScope {
            val friends = async { friendService.friendsOf(user, now, withFeed = false) }
            val clubs = async { clubService.clubsOf(user) }
            val games = async { playService.playOf(user, now).games.filter { it.match.mode != GameMode.SOLO } }
            Triple(friends.await(), clubs.await(), games.await())
        }
        return transaction(readOnly = true) {
            val memberships = clubMemberRepository.findMembers(clubs.clubs.map { it.membership.club }).filter { it.user.id != user.id }
            val sharingMembers = memberships.groupBy({ it.user.id }, { it.sharesSeen })
            val friendIds = friends.friends.map { it.id }.toSet()
            val members = memberships.map { it.user }.filter { it.id !in friendIds }.distinctBy { it.id }
            // Everyone, as they share: a friend as a friend, a member as a member of a club both are in.
            val everyone = friends.friends + members.filter { member -> sharingMembers[member.id].orEmpty().any { it } }.map { it.copy(shareSeen = true) }
            val checkIns = friendService.feed(everyone, FEED_LIMIT)
            val nights = movieNightRepository.findOpenOf(clubs.clubs.map { it.membership.club }, LocalDate.ofInstant(now, ZoneOffset.UTC))
            val voted = movieNightVoteRepository.findByUser(user, nights).map { it.night.entityId() }.toSet()
            CommunityView(
                friends = friends,
                clubs = clubs,
                feed = checkIns.ifEmpty { friendService.watches(everyone, FEED_LIMIT) },
                feedIsCheckIns = checkIns.isNotEmpty(),
                games = games,
                nights = nights.sortedBy { it.nightOn }.map { NightCard(it, answered = it.method == NightMethod.SWIPE || it.id in voted) },
                people = tasteOverlap.inCommon(user, friends.friends + members) { other ->
                    if (other.id in friendIds) other.shareSeen else sharingMembers[other.id].orEmpty().any { it }
                },
                typed = typedOf(user)
            )
        }
    }

    /** A friend's page; null for anyone who is not the visitor's friend. */
    suspend fun friendOf(user: User, friendId: String, now: Instant = Instant.now()): FriendView? {
        val (friend, games) = coroutineScope {
            val friend = async { transaction(readOnly = true) { userRepository.findById(friendId)?.takeIf { friendshipRepository.areFriends(user, it) } } }
            val games = async { playService.playOf(user, now).games.filter { it.match.mode == GameMode.FRIEND } }
            friend.await() to games.await()
        }
        if (friend == null) return null
        return transaction(readOnly = true) {
            FriendView(
                friend = friend,
                inCommon = tasteOverlap.inCommon(user, listOf(friend)) { it.shareSeen }.firstOrNull(),
                moviesInCommon = if (friend.shareSeen) movieSeenRepository.findSeenInCommon(user, friend, IN_COMMON_SHOWN).map { it.movie } else emptyList(),
                feed = friendService.feed(listOf(friend), FEED_LIMIT),
                games = games.filter { entry -> gamePlayerRepository.findPlayers(entry.match).any { it.user.id == friend.id } },
                typed = typedOf(user),
                topTen = if (friend.shareSeen) topTenRepository.findAll(friend).filter { !it.mention }.map { it.movie } else emptyList(),
                mentions = if (friend.shareSeen) topTenRepository.findAll(friend).filter { it.mention }.map { it.movie } else emptyList()
            )
        }
    }

    /**
     * What the keyboard behind the title types: the titles of the visitor's
     * latest movies, each followed by a star or the director of it, so the
     * keys spell out what they watched. Empty for someone who watched nothing.
     */
    private fun typedOf(user: User): List<String> {
        val movies = movieSeenRepository.findMostRecent(user, TYPED_MOVIES).map { it.movie }
        if (movies.isEmpty()) return emptyList()
        val people = principalRepository.findTasteCredits(movies.map { it.ref() }, TYPED_BILLING)
            .filter { it.category == DIRECTOR || it.category in ACTING_CATEGORIES }
            .groupBy({ it.movie.entityId() }, { it.person.primaryName })
        return movies.flatMap { movie -> listOf(movie.primaryTitle) + people[movie.id].orEmpty().take(1) }.distinct()
    }

    /**
     * The movies the visitor and another person both watched, the other's
     * latest first, as far as the other shares what they watch: as a friend,
     * or as a member of a club both are in. Null for anyone else.
     */
    suspend fun moviesInCommon(user: User, otherId: String, limit: Int): List<Movie>? = transaction(readOnly = true) {
        val other = userRepository.findById(otherId) ?: return@transaction null
        val shares = if (friendshipRepository.areFriends(user, other)) other.shareSeen else {
            val mine = clubMemberRepository.findClubs(user).map { it.club.id }.toSet()
            clubMemberRepository.findClubs(other).any { it.club.id in mine && it.sharesSeen }
        }
        if (!shares) return@transaction null
        movieSeenRepository.findSeenInCommon(user, other, limit).map { it.movie }
    }

    companion object {
        /** The rows of the keyboard behind the title, as a real one lays them out, the space bar last. */
        val KEYBOARD: List<List<String>> = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM").map { row -> row.map { it.toString() } } + listOf(listOf(" "))
        private const val TYPED_MOVIES = 10
        private const val TYPED_BILLING = 2
        private const val FEED_LIMIT = 36
        /** The movies in common a friend's page shows. */
        private const val IN_COMMON_SHOWN = 18
    }
}
