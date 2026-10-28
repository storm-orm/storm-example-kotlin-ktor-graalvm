-- A check-in is a watch on a day, and a movie can be watched more than once:
-- each watch is a row of its own. A seen movie stays one row of movie_seen,
-- with its vote; a seen movie without check-ins is history, seen at some time
-- before, so the history flag goes. An IMDb export that rates a title more
-- than once brings a check-in for each, matched to a check-in made here within
-- a week of it rather than counted twice.
CREATE TABLE check_in (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    watched_at TIMESTAMP NOT NULL
);

-- The check-ins page and the friends feed read a user's latest first; the
-- movie page and the import read one movie's check-ins.
CREATE INDEX idx_check_in_user_id_watched_at ON check_in (user_id, watched_at DESC, id DESC);
CREATE INDEX idx_check_in_user_id_movie_id ON check_in (user_id, movie_id);

-- Every check-in so far becomes a row, on its day.
INSERT INTO check_in (user_id, movie_id, watched_at)
SELECT user_id, movie_id, seen_at FROM movie_seen WHERE NOT historic;

ALTER TABLE movie_seen DROP COLUMN historic;
