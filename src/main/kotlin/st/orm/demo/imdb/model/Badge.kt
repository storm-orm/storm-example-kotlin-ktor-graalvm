package st.orm.demo.imdb.model

import st.orm.Entity
import st.orm.FK
import st.orm.GenerationStrategy.NONE
import st.orm.PK
import st.orm.Persist
import st.orm.Ref
import st.orm.template.ref
import java.time.Instant

/** What a badge is for: watching, checking in, the games, or the company. */
enum class BadgeCategory(val label: String) {
    WATCHING("Watching"),
    CHECK_INS("Checking in"),
    GAMES("Playing"),
    SOCIAL("Together")
}

/**
 * The badges: each earned by reaching its [goal] in something the site
 * counts, and kept once earned. [mark] is the short text on the medal.
 */
enum class Badge(val category: BadgeCategory, val title: String, val blurb: String, val goal: Int, val mark: String) {
    FIRST_REEL(BadgeCategory.WATCHING, "First Reel", "Your first movie watched.", 1, "1"),
    GOLDEN_AGE(BadgeCategory.WATCHING, "Golden Age", "Ten movies from before 1970.", 10, "50s"),
    NEW_HOLLYWOOD(BadgeCategory.WATCHING, "New Hollywood", "Ten movies from the 1970s and 80s.", 10, "70s"),
    NINETIES(BadgeCategory.WATCHING, "Nineties Kid", "Ten movies from the 1990s.", 10, "90s"),
    THIS_CENTURY(BadgeCategory.WATCHING, "This Century", "Twenty-five movies from 2000 on.", 25, "21"),
    HORROR_HOUND(BadgeCategory.WATCHING, "Horror Hound", "Fifteen horror movies.", 15, "H"),
    ANIMATED(BadgeCategory.WATCHING, "Animated", "Fifteen animated movies.", 15, "A"),
    DOCUMENTARIAN(BadgeCategory.WATCHING, "Documentarian", "Five documentaries.", 5, "D"),
    LAUGH_TRACK(BadgeCategory.WATCHING, "Laugh Track", "Fifteen comedies.", 15, "C"),
    FAR_OUT(BadgeCategory.WATCHING, "Far Out", "Fifteen science fiction movies.", 15, "SF"),
    WORLD_CINEMA(BadgeCategory.WATCHING, "World Cinema", "Movies in five languages.", 5, "W"),
    CHECKED_IN(BadgeCategory.CHECK_INS, "Checked In", "Your first check-in: a movie watched on a day.", 1, "✓"),
    DOUBLE_BILL(BadgeCategory.CHECK_INS, "Double Bill", "Two movies checked in on one day.", 2, "2x"),
    REGULAR(BadgeCategory.CHECK_INS, "Regular", "A check-in in four weeks running.", 4, "4w"),
    NIGHT_SHIFT(BadgeCategory.CHECK_INS, "Night Shift", "Ten check-ins.", 10, "10"),
    BACON_NUMBER(BadgeCategory.GAMES, "Bacon Number", "A link through Kevin Bacon in Movie Chain or Connect.", 1, "KB"),
    CHAIN_GANG(BadgeCategory.GAMES, "Chain Gang", "Five links of your own in one Movie Chain.", 5, "5"),
    PERFECT_GRID(BadgeCategory.GAMES, "Perfect Grid", "Movie Connections solved without a mistake.", 1, "4/4"),
    ODD_EYE(BadgeCategory.GAMES, "Odd Eye", "Odd One Out, ten out of ten.", 1, "10"),
    CAST_CALL(BadgeCategory.GAMES, "Cast Call", "A Name That Cast bid of ten or more, named in full.", 1, "10+"),
    DAILY_WEEK(BadgeCategory.GAMES, "Daily Week", "A daily played to the end seven days running.", 7, "7d"),
    FIRST_FRIEND(BadgeCategory.SOCIAL, "First Friend", "Your first friend on the site.", 1, "+1"),
    CLUB_MEMBER(BadgeCategory.SOCIAL, "Club Member", "In a club.", 1, "C"),
    NIGHT_HOST(BadgeCategory.SOCIAL, "Night Host", "Hosted a movie night.", 1, "N")
}

data class UserBadgePk(
    val userId: String,
    val badge: Badge
)

/** A badge a user earned, and when: kept whatever happens to what earned it. */
data class UserBadge(
    @PK(generation = NONE) val id: UserBadgePk,
    @FK @Persist(insertable = false, updatable = false) val user: Ref<User>,
    val earnedAt: Instant
) : Entity<UserBadgePk> {
    constructor(user: User, badge: Badge, earnedAt: Instant) : this(
        id = UserBadgePk(userId = user.id, badge = badge),
        user = user.ref(),
        earnedAt = earnedAt
    )

    val badge: Badge get() = id.badge
}
