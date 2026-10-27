-- A movie's whole cast and its directors, as TMDB lists them: IMDb's datasets
-- name only the top-billed. Kept with the rest of TMDB's answer, six months.
ALTER TABLE movie_source ADD COLUMN credits VARCHAR;

-- Name That Cast: the movie the house picked for a match.
CREATE TABLE cast_round (
    match_id BIGINT PRIMARY KEY REFERENCES game_match (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE
);

-- How many of the cast each player says they can name: the highest names them.
CREATE TABLE cast_bid (
    match_id BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    count    INTEGER NOT NULL,
    bid_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, user_id)
);

-- The names given, in their order: the credit each one matched, or none when it was wrong.
CREATE TABLE cast_answer (
    match_id    BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    user_id     VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    answer      VARCHAR NOT NULL,
    credit      VARCHAR,
    answered_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, position)
);
