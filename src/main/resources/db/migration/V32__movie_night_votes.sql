-- A member votes for as many of a night's candidates as they like, once each.
ALTER TABLE movie_night_vote DROP CONSTRAINT movie_night_vote_pkey;
ALTER TABLE movie_night_vote ADD PRIMARY KEY (night_id, user_id, movie_id);
