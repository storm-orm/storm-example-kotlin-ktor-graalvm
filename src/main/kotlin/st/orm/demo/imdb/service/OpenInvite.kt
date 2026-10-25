package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Invite
import java.time.Duration
import java.time.Instant

/**
 * A personal invite link still open, as the friends page and a club's page
 * list it: its token, for the link, and its age and what is left of its two
 * weeks in whole days, for "made 2 days ago, open 12 more days".
 */
data class OpenInvite(val token: String, val daysOld: Long, val daysLeft: Long) {
    companion object {
        fun of(invite: Invite, now: Instant) = OpenInvite(
            token = invite.token,
            daysOld = Duration.between(invite.createdAt, now).toDays(),
            daysLeft = Math.ceilDiv(Duration.between(now, invite.createdAt.plus(Invite.VALIDITY)).toSeconds(), SECONDS_PER_DAY)
        )

        /** A day begun counts as a day left: a link made a minute ago is open 14 more days. */
        private const val SECONDS_PER_DAY = 24L * 60 * 60
    }
}
