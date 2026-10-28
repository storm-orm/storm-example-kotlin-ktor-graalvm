-- The countries a movie was made in are stored since V24, by the Wikidata
-- run, which asks about a movie every six months: every movie is asked
-- again at the next start, so the countries fill now.
DELETE FROM movie_source WHERE kind = 'wikidata';
