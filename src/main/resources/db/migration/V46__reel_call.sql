-- Reel Call: a round of the cast game the other way round, of a person whose
-- movies are to be named. A round is of a movie or of a person.
ALTER TABLE cast_round ALTER COLUMN movie_id DROP NOT NULL;
ALTER TABLE cast_round ADD COLUMN person_id VARCHAR REFERENCES person (id) ON DELETE CASCADE;
