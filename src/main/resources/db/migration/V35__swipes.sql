-- Swipes: a user's latest swipe on a movie, which the deck never deals
-- again and whose taste agrees with whose; and the swipe rounds with a
-- friend or a club, each its own deck, and each player's swipes in it.
CREATE TABLE swipe (
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    way        VARCHAR NOT NULL,
    swiped_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);
CREATE INDEX idx_swipe_movie_id ON swipe (movie_id);

CREATE TABLE swipe_round_card (
    match_id BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    PRIMARY KEY (match_id, position)
);

CREATE TABLE swipe_round_swipe (
    match_id  BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    way       VARCHAR NOT NULL,
    swiped_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, user_id, movie_id)
);
