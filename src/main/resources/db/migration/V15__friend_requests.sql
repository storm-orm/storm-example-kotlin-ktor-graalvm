-- Friend requests between members of a club: a member asks another to be
-- friends, and the other accepts, or not now, which the requester is never
-- told. A request names the club it was sent from, and goes when either of
-- the two leaves that club, or the club closes.
CREATE TABLE friend_request (
    requester_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    recipient_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    club_id      BIGINT NOT NULL REFERENCES club (id) ON DELETE CASCADE,
    created_at   TIMESTAMP NOT NULL,
    dismissed    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (requester_id, recipient_id),
    CHECK (requester_id <> recipient_id)
);

CREATE INDEX idx_friend_request_recipient_id ON friend_request (recipient_id);
CREATE INDEX idx_friend_request_club_id ON friend_request (club_id);
