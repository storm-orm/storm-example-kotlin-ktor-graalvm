-- The titles without their accents, which the searches read (see V5). The trigram
-- indexes of V2 move to them, with the statistics sample of V3. The old indexes go
-- first: adding a stored column rewrites the table and rebuilds every index on it.
DROP INDEX idx_movie_primary_title_trgm;
DROP INDEX idx_movie_original_title_trgm;

ALTER TABLE movie
    ADD COLUMN primary_title_folded TEXT GENERATED ALWAYS AS (fold_accents(primary_title)) STORED,
    ADD COLUMN original_title_folded TEXT GENERATED ALWAYS AS (fold_accents(original_title)) STORED;

CREATE INDEX idx_movie_primary_title_folded_trgm ON movie USING gin (LOWER(primary_title_folded) gin_trgm_ops);
CREATE INDEX idx_movie_original_title_folded_trgm ON movie USING gin (LOWER(original_title_folded) gin_trgm_ops);

ALTER INDEX idx_movie_primary_title_folded_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_movie_original_title_folded_trgm ALTER COLUMN 1 SET STATISTICS 1000;

ANALYZE movie;
