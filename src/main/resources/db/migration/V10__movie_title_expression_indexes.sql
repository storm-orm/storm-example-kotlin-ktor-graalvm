-- The searches compare LOWER(fold_accents(column)) LIKE LOWER(fold_accents(?)),
-- applying the function of V5 to the column and to the input alike. Trigram
-- indexes on that expression serve them, so no column needs to store the folded
-- text. The generated columns of V6 and V7 stay until the next release: the
-- release that runs this migration replaces one that still reads them.
CREATE INDEX idx_movie_primary_title_fold_trgm ON movie USING gin (LOWER(fold_accents(primary_title)) gin_trgm_ops);
CREATE INDEX idx_movie_original_title_fold_trgm ON movie USING gin (LOWER(fold_accents(original_title)) gin_trgm_ops);

ALTER INDEX idx_movie_primary_title_fold_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_movie_original_title_fold_trgm ALTER COLUMN 1 SET STATISTICS 1000;

ANALYZE movie;
