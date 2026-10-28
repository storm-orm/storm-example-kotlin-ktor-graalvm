package st.orm.demo.imdb.service

import st.orm.demo.imdb.model.Invite
import java.time.Duration
import java.time.Instant

/**
 * An invite link still open, as the friends page and a club's page list it:
 * its token, for the link, whether it is the [public] one, and its age in
 * whole days; a personal one also what is left of its two weeks, for "made 2
 * days ago, open 12 more days". A public one is open until it is replaced.
 */
data class OpenInvite(val token: String, val public: Boolean, val daysOld: Long, val daysLeft: Long?, val accepted: Int) {
    companion object {
        fun of(invite: Invite, now: Instant) = OpenInvite(
            token = invite.token,
            public = invite.reusable,
            accepted = invite.acceptedCount,
            daysOld = Duration.between(invite.createdAt, now).toDays(),
            daysLeft = if (invite.reusable) null else Math.ceilDiv(Duration.between(now, invite.createdAt.plus(Invite.VALIDITY)).toSeconds(), SECONDS_PER_DAY)
        )

        /** A day begun counts as a day left: a link made a minute ago is open 14 more days. */
        private const val SECONDS_PER_DAY = 24L * 60 * 60
    }
}
