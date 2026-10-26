-- A day's quiz is picked per taste, the players' movie filter as its key, so
-- each player guesses a movie among the ones they watch; the day picked so far
-- is the one for every movie. A game keeps its movie.
ALTER TABLE quiz_day ADD COLUMN filter_key VARCHAR NOT NULL DEFAULT '';
ALTER TABLE quiz_day DROP CONSTRAINT quiz_day_pkey;
ALTER TABLE quiz_day ADD PRIMARY KEY (quiz_on, filter_key);
ALTER TABLE quiz_day ALTER COLUMN filter_key DROP DEFAULT;

ALTER TABLE quiz_play ADD COLUMN movie_id VARCHAR REFERENCES movie (id) ON DELETE CASCADE;
UPDATE quiz_play SET movie_id = (SELECT d.movie_id FROM quiz_day d WHERE d.quiz_on = quiz_play.quiz_on AND d.filter_key = '');
DELETE FROM quiz_play WHERE movie_id IS NULL;
ALTER TABLE quiz_play ALTER COLUMN movie_id SET NOT NULL;
