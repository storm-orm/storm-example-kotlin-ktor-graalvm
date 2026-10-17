-- Search matches every word anywhere in a title or name, ignoring case:
-- LOWER(column) LIKE LOWER('%word%'). A leading wildcard rules out a btree
-- index, so without these each search lowercases every row of person and
-- movie. Trigram GIN indexes on the lowered columns serve that predicate as it
-- is, for words of three characters or more.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_movie_primary_title_trgm ON movie USING gin (LOWER(primary_title) gin_trgm_ops);
CREATE INDEX idx_movie_original_title_trgm ON movie USING gin (LOWER(original_title) gin_trgm_ops);
CREATE INDEX idx_person_primary_name_trgm ON person USING gin (LOWER(primary_name) gin_trgm_ops);
