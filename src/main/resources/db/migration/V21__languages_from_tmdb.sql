-- Wikidata names no language for some movies, and TMDB is now asked the
-- language of those (WikidataService): the movies Wikidata was asked about
-- that have no language are asked again, at the next run.
DELETE FROM movie_source s
WHERE s.kind = 'wikidata'
  AND NOT EXISTS (SELECT 1 FROM movie_language l WHERE l.movie_id = s.movie_id);
