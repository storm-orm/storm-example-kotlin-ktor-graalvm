-- Clubs: a signed-in visitor starts a club, a film club of coworkers or of
-- friends, and its admins invite others with a link, as friends do. Members need not be
-- friends; each sees what the others watched, as far as each shares it.

-- What a member shares in clubs unless set apart for one club: what they
-- watch, their name with it (else "a member"), and the days, which are off
-- by default, as a club's members are not always friends.
ALTER TABLE app_user ADD COLUMN club_share_seen BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN club_share_name BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN club_share_dates BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE club (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- A membership, keyed by the pair, with the member's role and what they
-- share in this club: a NULL follows the member's club defaults above.
CREATE TABLE club_member (
    club_id     BIGINT NOT NULL REFERENCES club (id) ON DELETE CASCADE,
    user_id     VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    admin       BOOLEAN NOT NULL,
    joined_at   TIMESTAMP NOT NULL,
    share_seen  BOOLEAN,
    share_name  BOOLEAN,
    share_dates BOOLEAN,
    PRIMARY KEY (club_id, user_id)
);

CREATE INDEX idx_club_member_user_id ON club_member (user_id);

-- Invites of both kinds share one table: the friend invites become rows of
-- the kind 'friend', and an admin's invite to a club is of the kind 'club',
-- with the club. The tokens stay, so the links out there keep working.
ALTER TABLE friend_invite RENAME TO invite;
ALTER INDEX idx_friend_invite_inviter_id RENAME TO idx_invite_inviter_id;
ALTER TABLE invite ADD COLUMN kind VARCHAR NOT NULL DEFAULT 'friend';
ALTER TABLE invite ALTER COLUMN kind DROP DEFAULT;
ALTER TABLE invite ADD COLUMN club_id BIGINT REFERENCES club (id) ON DELETE CASCADE;
ALTER TABLE invite ADD CONSTRAINT invite_club_kind CHECK ((kind = 'club') = (club_id IS NOT NULL));

CREATE INDEX idx_invite_club_id ON invite (club_id);
