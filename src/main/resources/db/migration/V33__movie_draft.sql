-- Movie Draft: a match's theme, a genre and a decade, and its picks in order,
-- a movie once per draft; the house's picks, in a draft on one's own, have no player.
CREATE TABLE draft_theme (
    match_id BIGINT PRIMARY KEY REFERENCES game_match (id) ON DELETE CASCADE,
    genre_id INTEGER NOT NULL REFERENCES genre (id),
    decade   INTEGER NOT NULL
);

CREATE TABLE draft_pick (
    match_id  BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    player_id VARCHAR REFERENCES app_user (id) ON DELETE SET NULL,
    auto      BOOLEAN NOT NULL,
    picked_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, position),
    UNIQUE (match_id, movie_id)
);
