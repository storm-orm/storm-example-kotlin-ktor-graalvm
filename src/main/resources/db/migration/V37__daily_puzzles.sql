-- The daily puzzles besides the quiz, Odd One Out and Movie Connections: a
-- day's puzzles per game and taste, as JSON, and each player's game of them.
CREATE TABLE daily_puzzle (
    puzzle_on  DATE NOT NULL,
    game       VARCHAR NOT NULL,
    filter_key VARCHAR NOT NULL,
    data       VARCHAR NOT NULL,
    PRIMARY KEY (puzzle_on, game, filter_key)
);

CREATE TABLE daily_play (
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    puzzle_on  DATE NOT NULL,
    game       VARCHAR NOT NULL,
    filter_key VARCHAR NOT NULL,
    answers    VARCHAR NOT NULL,
    score      INTEGER NOT NULL,
    mistakes   INTEGER NOT NULL,
    finished   BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, puzzle_on, game)
);
CREATE INDEX idx_daily_play_day_game ON daily_play (puzzle_on, game);
