-- How long a poster was looked at before it was swiped, as the page measures
-- it: the quickest yes wins a tie in a round, and tells a player's wants apart.
ALTER TABLE swipe ADD COLUMN decided_ms INTEGER;
ALTER TABLE swipe_round_swipe ADD COLUMN decided_ms INTEGER;
