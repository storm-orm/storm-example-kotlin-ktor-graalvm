-- The daily quiz: the movie of each day, picked the first time the day's quiz
-- is opened, and each player's game of a day, their guesses as JSON. A club's
-- board reads its members' games of a day; a player's streak, their days.
CREATE TABLE quiz_day (
    quiz_on  DATE PRIMARY KEY,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE
);

CREATE TABLE quiz_play (
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    quiz_on    DATE NOT NULL,
    guesses    VARCHAR NOT NULL,
    solved     BOOLEAN NOT NULL,
    finished   BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, quiz_on)
);
CREATE INDEX idx_quiz_play_quiz_on ON quiz_play (quiz_on);
