-- A seen movie is a check-in or history. A check-in was watched on the day it
-- was marked, seen_at, which the tags and friends see. History was seen at
-- some time before and marked later: seen_at is only when it was marked, and
-- no page shows it. The catch-up page marks history; the movie page asks.
ALTER TABLE movie_seen ADD COLUMN historic BOOLEAN NOT NULL DEFAULT FALSE;

-- Once, at this release: the movies marked in the past week were mostly a
-- catching up on what was seen long before, so they become history. An IMDb
-- import run again restores the check-ins it has days for.
UPDATE movie_seen SET historic = TRUE WHERE seen_at > CURRENT_TIMESTAMP - INTERVAL '7 days';

-- The catch-up page lists the most voted movies first, in keyset windows.
CREATE INDEX idx_rating_vote_count ON rating (vote_count DESC, movie_id);
