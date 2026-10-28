-- How a movie night's movie is chosen: a vote on the candidates, or a swipe
-- round of the club on them, the most wanted winning; the round, for the latter.
ALTER TABLE movie_night ADD COLUMN method VARCHAR NOT NULL DEFAULT 'VOTE';
ALTER TABLE movie_night ADD COLUMN swipe_match_id BIGINT REFERENCES game_match (id) ON DELETE SET NULL;
