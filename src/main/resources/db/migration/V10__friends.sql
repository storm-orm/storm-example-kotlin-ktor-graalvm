-- Friends: a signed-in visitor invites another with a link, and once accepted
-- each sees what the other watched lately, as far as the other shares it.

-- What a visitor shares with friends, each on by default: what they watch at
-- all, their name with it (else it shows as "a friend"), and the days.
ALTER TABLE app_user ADD COLUMN share_seen BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN share_name BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN share_dates BOOLEAN NOT NULL DEFAULT TRUE;

-- A friendship is two rows, one each way, so a visitor's friends are a lookup
-- of their own rows by the key, and ending it removes both.
CREATE TABLE friendship (
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    friend_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    since     TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, friend_id),
    CHECK (user_id <> friend_id)
);

CREATE INDEX idx_friendship_friend_id ON friendship (friend_id);

-- An invite link: a random token that one visitor can accept, once, within
-- two weeks. The person who created it has asked already, so accepting it is
-- the whole friend request.
CREATE TABLE friend_invite (
    token      VARCHAR NOT NULL PRIMARY KEY,
    inviter_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_friend_invite_inviter_id ON friend_invite (inviter_id);
