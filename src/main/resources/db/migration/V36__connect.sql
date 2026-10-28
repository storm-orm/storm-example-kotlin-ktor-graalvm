-- Connect: a puzzle's start and target movies, par, the fewest links between
-- them, with a way that takes that many, and the team's hints and undos. The
-- chain the players build is kept as chain links, as Movie Chain's is.
CREATE TABLE connect_puzzle (
    match_id   BIGINT PRIMARY KEY REFERENCES game_match (id) ON DELETE CASCADE,
    start_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    target_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    par        INTEGER NOT NULL,
    path       VARCHAR NOT NULL,
    hints      INTEGER NOT NULL DEFAULT 0,
    undos      INTEGER NOT NULL DEFAULT 0,
    hint_at    INTEGER,
    hint_shown INTEGER NOT NULL DEFAULT 0
);
