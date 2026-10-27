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
 * counts, and kept once earned. [mark] is the short text that stands for it,
 * [icon] the drawing on its medal (fragments/badges.html), [tone] its colour.
 */
enum class Badge(val category: BadgeCategory, val title: String, val blurb: String, val goal: Int, val mark: String, val icon: String, val tone: String) {
    FIRST_REEL(BadgeCategory.WATCHING, "First Reel", "Your first movie watched.", 1, "1", "reel", "pink"),
    GOLDEN_AGE(BadgeCategory.WATCHING, "Golden Age", "Ten movies from before 1970.", 10, "50s", "clapper", "yellow"),
    NEW_HOLLYWOOD(BadgeCategory.WATCHING, "New Hollywood", "Ten movies from the 1970s and 80s.", 10, "70s", "camera", "orange"),
    NINETIES(BadgeCategory.WATCHING, "Nineties Kid", "Ten movies from the 1990s.", 10, "90s", "tape", "cyan"),
    THIS_CENTURY(BadgeCategory.WATCHING, "This Century", "Twenty-five movies from 2000 on.", 25, "21", "disc", "violet"),
    HORROR_HOUND(BadgeCategory.WATCHING, "Horror Hound", "Fifteen horror movies.", 15, "H", "ghost", "green"),
    ANIMATED(BadgeCategory.WATCHING, "Animated", "Fifteen animated movies.", 15, "A", "pencil", "orange"),
    DOCUMENTARIAN(BadgeCategory.WATCHING, "Documentarian", "Five documentaries.", 5, "D", "lens", "cyan"),
    LAUGH_TRACK(BadgeCategory.WATCHING, "Laugh Track", "Fifteen comedies.", 15, "C", "smile", "yellow"),
    FAR_OUT(BadgeCategory.WATCHING, "Far Out", "Fifteen science fiction movies.", 15, "SF", "rocket", "violet"),
    WORLD_CINEMA(BadgeCategory.WATCHING, "World Cinema", "Movies in five languages.", 5, "W", "globe", "green"),
    YIPPEE_KI_YAY(BadgeCategory.WATCHING, "Yippee-Ki-Yay", "Five Bruce Willis movies. He stepped away in 2022; the movies stay.", 5, "BW", "tower", "orange"),
    CHECKED_IN(BadgeCategory.CHECK_INS, "Checked In", "Your first check-in: a movie watched on a day.", 1, "✓", "check", "cyan"),
    DOUBLE_BILL(BadgeCategory.CHECK_INS, "Double Bill", "Two movies checked in on one day.", 2, "2x", "tickets", "pink"),
    REGULAR(BadgeCategory.CHECK_INS, "Regular", "A check-in in four weeks running.", 4, "4w", "calendar", "cyan"),
    NIGHT_SHIFT(BadgeCategory.CHECK_INS, "Night Shift", "Ten check-ins.", 10, "10", "moon", "violet"),
    DECK_DONE(BadgeCategory.GAMES, "Deck Done", "A deck of thirty swiped, on your own or in a round.", 30, "30", "cards", "pink"),
    HOT_STREAK(BadgeCategory.GAMES, "Hot Streak", "Three right in a row at Top Gun.", 3, "3", "flame", "orange"),
    FIRST_DAILY(BadgeCategory.GAMES, "First Daily", "A daily played to the end.", 1, "1", "sun", "yellow"),
    WINNER(BadgeCategory.GAMES, "Winner", "Top Gun or Chain Reaction won against a friend or a club.", 1, "W", "trophy", "cyan"),
    CHAMPION(BadgeCategory.GAMES, "Champion", "Five games won against friends or clubs.", 5, "5", "crown", "yellow"),
    BACON_NUMBER(BadgeCategory.GAMES, "Bacon Number", "A link through Kevin Bacon in a chain or in Six Degrees of Separation.", 1, "KB", "bacon", "orange"),
    STREEP_NUMBER(BadgeCategory.GAMES, "Streep Number", "A link through Meryl Streep in a chain: the GOAT's own rite.", 1, "MS", "statuette", "yellow"),
    CHAIN_GANG(BadgeCategory.GAMES, "Chain Gang", "Five links of your own in one chain.", 5, "5", "chain", "green"),
    PERFECT_GRID(BadgeCategory.GAMES, "Perfect Grid", "The Breakfast Club solved without a mistake.", 1, "4/4", "grid", "cyan"),
    ODD_EYE(BadgeCategory.GAMES, "Odd Eye", "The Outsiders, ten out of ten.", 1, "10", "eye", "pink"),
    CAST_CALL(BadgeCategory.GAMES, "Cast Call", "A Total Recall bid of ten or more, named in full.", 1, "10+", "masks", "violet"),
    DAILY_WEEK(BadgeCategory.GAMES, "Daily Week", "A daily played to the end seven days running.", 7, "7d", "seven", "yellow"),
    FIRST_FRIEND(BadgeCategory.SOCIAL, "First Friend", "Your first friend on the site.", 1, "+1", "friends", "pink"),
    CLUB_MEMBER(BadgeCategory.SOCIAL, "Club Member", "In a club.", 1, "C", "club", "violet"),
    NIGHT_HOST(BadgeCategory.SOCIAL, "Night Host", "Hosted a movie night.", 1, "N", "house", "orange"),
    STAR_SPONSOR(BadgeCategory.SOCIAL, "Star Sponsor", "Paid for your star, as one does on the Walk of Fame. Here it was on the house.", 1, "$", "dollar", "yellow")
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
