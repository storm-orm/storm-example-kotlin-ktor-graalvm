-- Personal accounts: the watchlist and the seen list belong to a visitor.
--
-- An account starts as a random id in the visitor's cookie, with no sign-up.
-- Its row is written on the first thing the visitor saves. Signing in with a
-- git provider (GitHub, GitLab, Bitbucket, Codeberg) links the provider's
-- account to it in user_login, which is what lets the lists follow the
-- visitor to another browser. The user table is app_user because user is a
-- reserved word in PostgreSQL.
CREATE TABLE app_user (
    id           VARCHAR NOT NULL PRIMARY KEY,
    name         VARCHAR,
    avatar_url   VARCHAR,
    created_at   TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL
);

-- One row per provider account linked to a user. The subject is the id the
-- provider keeps stable for an account; the username is the login name at
-- the time of linking, for display only.
CREATE TABLE user_login (
    provider  VARCHAR NOT NULL,
    subject   VARCHAR NOT NULL,
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    username  VARCHAR NOT NULL,
    linked_at TIMESTAMP NOT NULL,
    PRIMARY KEY (provider, subject)
);

CREATE INDEX idx_user_login_user_id ON user_login (user_id);

-- The watchlist was one list shared by every visitor; it becomes one per user,
-- keyed by the pair, so saving a movie twice stays a key violation. The shared
-- rows belonged to nobody and go with the table.
DROP TABLE watchlist;

CREATE TABLE watchlist (
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

CREATE INDEX idx_watchlist_user_id_added_at ON watchlist (user_id, added_at DESC);

-- The movies a user has marked as seen, the same shape as the watchlist.
CREATE TABLE movie_seen (
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    seen_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

CREATE INDEX idx_movie_seen_user_id_seen_at ON movie_seen (user_id, seen_at DESC);
