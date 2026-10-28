-- The searches match and rank movies by their titles without accents. Folding a
-- title through unaccent costs microseconds, which a trigram index cannot save:
-- the index finds candidate rows, and the database then rechecks each one and
-- computes the ranks from the title itself. A common word such as "the" matches
-- a third of the catalogue, so a search folded tens of thousands of titles per
-- keystroke. Storing the folded titles moves that work to the writes; the
-- searches read the stored text, and the trigram indexes are built on it.
--
-- Names stay folded at query time (V1): a search reads only the people its index
-- finds, a few thousand at most, and the Person entity holds no derived column.

-- Computing the columns and building the indexes takes longer than the role's
-- statement timeout on a large catalogue; the setting lasts for this migration.
SET LOCAL statement_timeout = '15min';

ALTER TABLE movie
    ADD COLUMN primary_title_folded  VARCHAR GENERATED ALWAYS AS (fold_accents(primary_title)) STORED NOT NULL,
    ADD COLUMN original_title_folded VARCHAR GENERATED ALWAYS AS (fold_accents(original_title)) STORED NOT NULL;

DROP INDEX idx_movie_primary_title_fold_trgm;
DROP INDEX idx_movie_original_title_fold_trgm;

CREATE INDEX idx_movie_primary_title_folded_trgm ON movie USING gin (LOWER(primary_title_folded) gin_trgm_ops);
CREATE INDEX idx_movie_original_title_folded_trgm ON movie USING gin (LOWER(original_title_folded) gin_trgm_ops);

-- As in V1: a larger sample keeps the planner's estimate of a word's matches near
-- the real count. Analyzing here gathers it before the first search.
ALTER INDEX idx_movie_primary_title_folded_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_movie_original_title_folded_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ANALYZE movie;
