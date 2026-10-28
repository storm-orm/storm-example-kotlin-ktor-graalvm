-- Who came through whose invite, friend or club, kept for good: a personal
-- invite goes when accepted and a public one when replaced, and the levels
-- count the people one brought in, so the count lives here.
CREATE TABLE invite_acceptance (
    inviter_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    invitee_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    accepted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (inviter_id, invitee_id)
);
