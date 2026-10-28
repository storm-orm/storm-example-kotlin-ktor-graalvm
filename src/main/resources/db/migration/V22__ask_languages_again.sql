-- The movies still without a language are asked again, so Wikidata and TMDB
-- name it where they can (WikidataService).
DELETE FROM movie_source s
WHERE s.kind = 'wikidata'
  AND NOT EXISTS (SELECT 1 FROM movie_language l WHERE l.movie_id = s.movie_id);
