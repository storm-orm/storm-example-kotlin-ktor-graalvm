package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Club
import st.orm.demo.imdb.model.Movie
import st.orm.demo.imdb.model.MovieList
import st.orm.demo.imdb.model.MovieNight
import st.orm.demo.imdb.model.MovieNightCandidate
import st.orm.demo.imdb.model.MovieNightProvider
import st.orm.demo.imdb.model.MovieNightVote
import st.orm.demo.imdb.model.MovieNightVotePk
import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.model.WatchProvider
import st.orm.demo.imdb.repository.CheckInRepository
import st.orm.demo.imdb.repository.ClubMemberRepository
import st.orm.demo.imdb.repository.ClubRepository
import st.orm.demo.imdb.repository.MovieListItemRepository
import st.orm.demo.imdb.repository.MovieListRepository
import st.orm.demo.imdb.repository.MovieNightCandidateRepository
import st.orm.demo.imdb.repository.MovieNightProviderRepository
import st.orm.demo.imdb.repository.MovieNightRepository
import st.orm.demo.imdb.repository.MovieNightVoteRepository
import st.orm.demo.imdb.repository.MovieRepository
import st.orm.demo.imdb.repository.MovieSeenRepository
import st.orm.demo.imdb.repository.RecommendationRepository
import st.orm.demo.imdb.repository.UserWatchProviderRepository
import st.orm.demo.imdb.repository.WatchProviderRepository
import st.orm.demo.imdb.repository.WatchlistRepository
import st.orm.template.entityId
import st.orm.template.ref
import st.orm.template.transaction
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A service a night can be watched on, for the planning form: the members who have it, and whether the host does. */
data class ServiceChoice(val service: WatchProvider, val members: List<String>, val yours: Boolean)

/**
 * The planning form of a club's movie night: the members' services, the
 * host's own ticked, the lists to pick from, the country the services and
 * the cinemas are of, whether TMDB knows what plays in its cinemas, so the
 * night can be there, and the next Friday to start from.
 */
data class PlanView(
    val club: Club,
    val services: List<ServiceChoice>,
    val lists: List<MovieList>,
    val country: Country?,
    val cinemas: Boolean,
    val day: LocalDate
)

/** A candidate of a night as its card shows it: the members who voted for it, and its share of the most votes. */
data class CandidateView(val candidate: MovieNightCandidate, val voters: List<User>, val share: Double) {
    val movie: Movie get() = candidate.movie
}

/**
 * A movie night's page: where it is watched, the candidates with their
 * votes, the visitor's own votes, and what they may do: pick the winner
 * ([canDecide]), the host or one of the club's admins, and, the night come,
 * check the winner in ([canCheckIn]) unless they did ([checkedIn]).
 */
data class NightView(
    val night: MovieNight,
    val services: List<WatchProvider>,
    val candidates: List<CandidateView>,
    /** The candidates the visitor voted for, by movie id. */
    val myVotes: Set<String>,
    /** How many members voted, for one candidate or more. */
    val voterCount: Int,
    val memberCount: Int,
    val canDecide: Boolean,
    val canCheckIn: Boolean,
    val checkedIn: Boolean
) {
    val decided: Boolean get() = night.winner != null
    val day: String get() = NIGHT_DAY.format(night.nightOn)
    val winner: CandidateView? get() = candidates.firstOrNull { it.movie.id == night.winner?.id }
}

/** A night's day as the pages say it: "Friday 31 October". */
private val NIGHT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/** A night on its club's page: its day as the page says it. */
data class NightSummary(val night: MovieNight) {
    val day: String get() = NIGHT_DAY.format(night.nightOn)
}

/** What came of planning a night. */
sealed interface Planned {
    data class Night(val night: MovieNight) : Planned
    /** Nothing to vote on where the night is watched: the members saw it all, or it plays nowhere chosen. */
    data object NothingFound : Planned
    data object NotAllowed : Planned
}

/**
 * Movie nights: a club member plans one, for a day, and chooses where the
 * club watches: on their own services, by default, or on other members'
 * too, as the night is not always at the host's, and at the cinema, from
 * what plays in the country's cinemas now. The candidates are the movies
 * none of the members has seen, from their watchlists and their picks, or
 * from one list, that play where the night is watched: the more members
 * have one on their watchlist, and the better it suits them, the higher it
 * ranks. The members vote, for as many as they like; the host, or a club
 * admin, picks the winner, which each member checks in once the night has come.
 *
 * Planning asks TMDB where the candidates stream and what the cinemas
 * play, outside any transaction, between the read of the club's lists and
 * the write of the night.
 */
class MovieNightService(
    private val clubRepository: ClubRepository,
    private val clubMemberRepository: ClubMemberRepository,
    private val movieNightRepository: MovieNightRepository,
    private val movieNightProviderRepository: MovieNightProviderRepository,
    private val movieNightCandidateRepository: MovieNightCandidateRepository,
    private val movieNightVoteRepository: MovieNightVoteRepository,
    private val movieRepository: MovieRepository,
    private val movieSeenRepository: MovieSeenRepository,
    private val watchlistRepository: WatchlistRepository,
    private val recommendationRepository: RecommendationRepository,
    private val movieListRepository: MovieListRepository,
    private val movieListItemRepository: MovieListItemRepository,
    private val userWatchProviderRepository: UserWatchProviderRepository,
    private val watchProviderRepository: WatchProviderRepository,
    private val checkInRepository: CheckInRepository,
    private val tmdbService: TmdbService
) {

    /* ── Planning ─────────────────────────────────────────────────── */

    /**
     * The planning form for a member of the club, or null for anyone else.
     * The cinema is offered where TMDB knows what plays in the country's
     * cinemas; elsewhere the night is at home.
     */
    suspend fun planFor(user: User, clubId: Long, country: Country?, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): PlanView? {
        // Asked before the transaction, as it may ask TMDB; kept for half a day, so the form rarely waits on it.
        val cinemas = country != null && tmdbService.nowPlayingIn(country.code).isNotEmpty()
        return planView(user, clubId, country, cinemas, today)
    }

    private suspend fun planView(user: User, clubId: Long, country: Country?, cinemas: Boolean, today: LocalDate): PlanView? = transaction(readOnly = true) {
        val club = clubRepository.findById(clubId) ?: return@transaction null
        clubMemberRepository.findMember(club, user.id) ?: return@transaction null
        val members = clubMemberRepository.findMembers(listOf(club)).map { it.user }
        val names = members.associate { it.id to (it.name ?: "A member") }
        val services = userWatchProviderRepository.findAllOf(members)
            .groupBy { it.watchProvider }
            .map { (service, rows) ->
                val ids = rows.map { it.user.entityId() }
                ServiceChoice(service, ids.filter { it != user.id }.map(names::getValue).sorted(), yours = user.id in ids)
            }
            // The host's own first, then the ones most members have.
            .sortedWith(compareByDescending<ServiceChoice> { it.yours }.thenByDescending { it.members.size }.thenBy { it.service.name })
        PlanView(
            club = club,
            services = services,
            lists = movieListRepository.findOfClubs(listOf(club)) + movieListRepository.findOwn(user),
            country = country,
            cinemas = cinemas,
            day = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        )
    }

    /** What a night is picked from: the members, what they saw, want to see and are recommended, or a list's movies. */
    private class Pool(
        val club: Club,
        val members: List<User>,
        val seen: Set<String>,
        val listed: Map<String, Long>,
        val fit: Map<String, Double>,
        val movies: Map<String, Movie>,
        val list: MovieList?,
        val services: List<WatchProvider>
    )

    /**
     * Plans a night: picks its candidates where it is watched, and stores
     * it with them. [serviceIds] are the services, [cinema] the cinemas of
     * the [country]; neither, and the night is anywhere. [listId] keeps the
     * candidates to a list of the club's or the host's.
     */
    suspend fun plan(
        user: User,
        clubId: Long,
        title: String,
        day: LocalDate,
        serviceIds: Set<Int>,
        cinema: Boolean,
        listId: Long?,
        country: String?,
        now: Instant = Instant.now()
    ): Planned {
        val pool = readPool(user, clubId, serviceIds, listId) ?: return Planned.NotAllowed
        val inCinemas = if (cinema && country != null) tmdbService.nowPlayingIn(country, now) else emptyList()
        val cinemaMovies = transaction(readOnly = true) { movieRepository.findAllById(inCinemas.filter { it !in pool.movies }) }.associateBy { it.id }
        val candidates = candidatesOf(pool, inCinemas, cinemaMovies, country, cinema)
        if (candidates.isEmpty()) return Planned.NothingFound
        return transaction {
            val night = movieNightRepository.insertAndFetch(MovieNight(
                club = pool.club,
                host = user,
                title = title.trim().take(TITLE_LENGTH).ifEmpty { DEFAULT_TITLE },
                nightOn = day,
                country = country,
                cinema = cinema,
                list = pool.list?.ref(),
                winner = null,
                createdAt = now
            ))
            movieNightProviderRepository.insert(pool.services.map { MovieNightProvider(night, it) })
            movieNightCandidateRepository.insert(candidates.mapIndexed { i, (movie, reason) -> MovieNightCandidate(night, movie, i, reason) })
            Planned.Night(night)
        }
    }

    private suspend fun readPool(user: User, clubId: Long, serviceIds: Set<Int>, listId: Long?): Pool? = transaction(readOnly = true) {
        val club = clubRepository.findById(clubId) ?: return@transaction null
        clubMemberRepository.findMember(club, user.id) ?: return@transaction null
        val members = clubMemberRepository.findMembers(listOf(club)).map { it.user }
        // A list of the club's, or one of the host's own.
        val list = listId?.let(movieListRepository::findById)?.takeIf { it.club?.id == club.id || (it.club == null && it.owner.id == user.id) }
        val listed = watchlistRepository.countListedBy(members)
        val recommendations = recommendationRepository.findAllOf(members)
        // How well a movie suits the club: each member's pick scores in its share of their best, averaged over the members.
        val best = recommendations.groupBy { it.user.entityId() }.mapValues { (_, picks) -> picks.maxOf { it.score.toDouble() }.coerceAtLeast(1e-9) }
        val fit = recommendations.groupBy { it.movie.id }
            .mapValues { (_, picks) -> picks.sumOf { it.score.toDouble() / best.getValue(it.user.entityId()) } / members.size }
        val movies = if (list != null) {
            movieListItemRepository.findItems(list).map { it.movie }
        } else {
            listed.map { it.movie } + recommendations.map { it.movie }
        }
        Pool(
            club = club,
            members = members,
            seen = movieSeenRepository.findSeenBy(members).map { it.movie.entityId() }.toSet(),
            listed = listed.associate { it.movie.id to it.users },
            fit = fit,
            movies = movies.associateBy { it.id },
            list = list,
            services = watchProviderRepository.findAllById(serviceIds)
        )
    }

    /**
     * The night's candidates, the best first, each with why it was picked:
     * the pool's movies nobody saw, ranked by how many members want to see
     * them and how well they suit the club, kept to the ones on the chosen
     * services, and the movies in the cinemas, ranked the same, then by
     * how popular they are. TMDB is asked where the best of the pool stream.
     */
    private suspend fun candidatesOf(
        pool: Pool,
        inCinemas: List<String>,
        cinemaMovies: Map<String, Movie>,
        country: String?,
        cinema: Boolean
    ): List<Pair<Movie, String>> {
        fun score(id: String) = (pool.listed[id] ?: 0) * LISTED_WEIGHT + (pool.fit[id] ?: 0.0) * FIT_WEIGHT
        val unseen = pool.movies.values.filter { it.id !in pool.seen }.sortedByDescending { score(it.id) }
        val found = linkedMapOf<String, Pair<Movie, MutableList<String>>>()
        fun add(movie: Movie, where: String) = found.getOrPut(movie.id) { movie to mutableListOf() }.second.add(where)

        if (cinema) {
            // In the cinemas: from the list, when there is one, else whatever plays, the club's favourites first, then the most popular.
            val playing = inCinemas.mapNotNull { pool.movies[it] ?: cinemaMovies[it].takeIf { pool.list == null } }.filter { it.id !in pool.seen }
            playing.withIndex()
                .sortedByDescending { (rank, movie) -> score(movie.id) + (playing.size - rank) * POPULARITY_WEIGHT / playing.size }
                .take(CANDIDATES)
                .forEach { add(it.value, "In cinemas") }
        }
        if (pool.services.isNotEmpty() && country != null) {
            val names = pool.services.associate { it.id to it.name }
            val looked = unseen.take(LOOKUPS)
            val available = tmdbService.watchProviderIds(looked, country, maxLookups = LOOKUPS)
            looked.forEach { movie ->
                available[movie.id].orEmpty().firstOrNull { it in names }?.let { add(movie, "On ${names.getValue(it)}") }
            }
        }
        if (!cinema && pool.services.isEmpty()) unseen.forEach { add(it, "") }

        return found.values
            .sortedByDescending { (movie, _) -> score(movie.id) }
            .let { if (cinema && pool.services.isNotEmpty()) mix(it) else it }
            .take(CANDIDATES)
            .map { (movie, where) -> movie to reasonOf(movie, where.first(), pool) }
    }

    /** A cinema night that may stream as well: the cinemas and the services take turns, so both are on the ballot. */
    private fun mix(found: List<Pair<Movie, MutableList<String>>>): List<Pair<Movie, MutableList<String>>> {
        val (cinema, streaming) = found.partition { "In cinemas" in it.second }
        return (0 until maxOf(cinema.size, streaming.size)).flatMap { listOfNotNull(cinema.getOrNull(it), streaming.getOrNull(it)) }
    }

    /** Why a candidate was picked, as its card says it: where it plays, and who wants to see it. */
    private fun reasonOf(movie: Movie, where: String, pool: Pool): String {
        val listed = pool.listed[movie.id] ?: 0
        val why = when {
            listed > 1 -> "on $listed watchlists"
            listed == 1L -> "on a watchlist"
            pool.list != null -> "from ${pool.list.name}"
            (pool.fit[movie.id] ?: 0.0) > 0 -> "suits the club"
            else -> null
        }
        return listOfNotNull(where.ifEmpty { null }, why).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    /* ── The night ────────────────────────────────────────────────── */

    /** A night's page for a member of its club, or null for anyone else. */
    suspend fun nightOf(user: User, nightId: Long, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): NightView? = transaction(readOnly = true) {
        val night = movieNightRepository.findById(nightId) ?: return@transaction null
        val me = clubMemberRepository.findMember(night.club, user.id) ?: return@transaction null
        val votes = movieNightVoteRepository.findVotes(night)
        val byMovie = votes.groupBy({ it.movie.entityId() }, { it.user })
        val most = byMovie.values.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val winner = night.winner
        NightView(
            night = night,
            services = movieNightProviderRepository.findServices(night),
            candidates = movieNightCandidateRepository.findCandidates(night).map {
                val voters = byMovie[it.movie.id].orEmpty()
                CandidateView(it, voters, 100.0 * voters.size / most)
            },
            myVotes = votes.filter { it.user.id == user.id }.map { it.movie.entityId() }.toSet(),
            voterCount = votes.map { it.user.id }.distinct().size,
            memberCount = clubMemberRepository.countMembers(night.club).toInt(),
            canDecide = night.host.id == user.id || me.admin,
            canCheckIn = winner != null && !night.nightOn.isAfter(today) && !night.nightOn.isBefore(today.minusDays(CHECK_IN_DAYS)),
            checkedIn = winner != null && checkInRepository.findAll(user, winner).any { LocalDate.ofInstant(it.watchedAt, ZoneOffset.UTC) == night.nightOn }
        )
    }

    /** A club's nights, the latest first, for its page. */
    suspend fun nightsOf(club: Club): List<NightSummary> = transaction(readOnly = true) {
        movieNightRepository.findOf(club, NIGHTS_LIMIT).map(::NightSummary)
    }

    /**
     * Votes for one of the night's candidates, or takes the vote back when
     * the member voted for it already: a member votes for as many as they
     * like. Nothing once the winner is picked, or for anyone not in the club.
     */
    suspend fun vote(user: User, nightId: Long, movieId: String, now: Instant = Instant.now()) = transaction {
        val night = movieNightRepository.findById(nightId)?.takeIf { it.winner == null } ?: return@transaction
        clubMemberRepository.findMember(night.club, user.id) ?: return@transaction
        val candidate = movieNightCandidateRepository.findCandidates(night).firstOrNull { it.movie.id == movieId } ?: return@transaction
        val existing = movieNightVoteRepository.findById(MovieNightVotePk(nightId = night.id, userId = user.id, movieId = movieId))
        if (existing != null) movieNightVoteRepository.remove(existing) else movieNightVoteRepository.insert(MovieNightVote(night, user, candidate.movie, now))
    }

    /**
     * Picks the night's movie, for the host or a club admin: the one with
     * the most votes, the better ranked of a tie, the best ranked without
     * any. [reopen] takes the pick back, for another round of votes.
     */
    suspend fun decide(user: User, nightId: Long, reopen: Boolean = false) = transaction {
        val night = movieNightRepository.findById(nightId) ?: return@transaction
        val me = clubMemberRepository.findMember(night.club, user.id) ?: return@transaction
        if (night.host.id != user.id && !me.admin) return@transaction
        if (reopen) {
            movieNightRepository.update(night.copy(winner = null))
            return@transaction
        }
        val votes = movieNightVoteRepository.findVotes(night).groupingBy { it.movie.entityId() }.eachCount()
        val winner = movieNightCandidateRepository.findCandidates(night)
            .maxWithOrNull(compareBy<MovieNightCandidate> { votes[it.movie.id] ?: 0 }.thenByDescending { it.position })
            ?: return@transaction
        movieNightRepository.update(night.copy(winner = winner.movie))
    }

    /** Removes a night, for the host or a club admin; returns its club's id, for the way back. */
    suspend fun remove(user: User, nightId: Long): Long? = transaction {
        val night = movieNightRepository.findById(nightId) ?: return@transaction null
        val me = clubMemberRepository.findMember(night.club, user.id) ?: return@transaction null
        if (night.host.id != user.id && !me.admin) return@transaction null
        movieNightRepository.remove(night)
        night.club.id
    }

    companion object {
        const val TITLE_LENGTH = 60
        const val DEFAULT_TITLE = "Movie night"
        private const val CANDIDATES = 6
        /** How many of the pool's best are looked up on TMDB for where they stream. */
        private const val LOOKUPS = 60
        private const val LISTED_WEIGHT = 1.0
        private const val FIT_WEIGHT = 2.0
        private const val POPULARITY_WEIGHT = 0.5
        private const val NIGHTS_LIMIT = 6
        /** A check-in is for a day of the past week (MovieSeenService). */
        private const val CHECK_IN_DAYS = 7L
    }
}
