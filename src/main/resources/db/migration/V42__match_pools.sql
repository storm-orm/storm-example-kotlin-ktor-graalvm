-- Where a match's movies came from: the kind of pool, how many it held, and
-- how many of them each player had watched when the match started, which
-- says who knew the pool.
ALTER TABLE game_match ADD COLUMN pool VARCHAR;
ALTER TABLE game_match ADD COLUMN pool_size INTEGER;
ALTER TABLE game_match ADD COLUMN pool_fallback BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE game_player ADD COLUMN pool_seen INTEGER;
