-- The games played as matches, on one's own, with a friend or with a club,
-- and their players. A round closes at closes_at, a match ends at ended_at;
-- both are read when a match is looked at, as no job runs in the background.
CREATE TABLE game_match (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game       VARCHAR NOT NULL,
    mode       VARCHAR NOT NULL,
    club_id    BIGINT REFERENCES club (id) ON DELETE CASCADE,
    host_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    closes_at  TIMESTAMP,
    ended_at   TIMESTAMP,
    CHECK ((mode = 'CLUB') = (club_id IS NOT NULL))
);
CREATE INDEX idx_game_match_club_id ON game_match (club_id, created_at DESC);
CREATE INDEX idx_game_match_host_id ON game_match (host_id, created_at DESC);

CREATE TABLE game_player (
    match_id   BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    turn_order INTEGER NOT NULL,
    score      INTEGER NOT NULL,
    finished   BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, user_id)
);
CREATE INDEX idx_game_player_user_id ON game_player (user_id);

-- Higher or Lower: the movies of a match in their order, with their ratings then.
CREATE TABLE game_card (
    match_id BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    rating   NUMERIC(3, 1) NOT NULL,
    PRIMARY KEY (match_id, position)
);

-- Movie Chain: the movies of a chain in their order, each sharing an actor with the one before.
CREATE TABLE chain_link (
    match_id  BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    player_id VARCHAR REFERENCES app_user (id) ON DELETE SET NULL,
    via_id    VARCHAR REFERENCES person (id),
    points    INTEGER NOT NULL,
    added_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, position)
);
