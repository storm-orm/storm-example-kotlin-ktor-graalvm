-- The trigram index of V10 for the person name. On the platform the person index
-- of V8 took about 20 seconds to build, near the 30-second limit on a statement,
-- so this migration's transaction allows its statements longer.
SET LOCAL statement_timeout = '50s';

CREATE INDEX idx_person_primary_name_fold_trgm ON person USING gin (LOWER(fold_accents(primary_name)) gin_trgm_ops);

ALTER INDEX idx_person_primary_name_fold_trgm ALTER COLUMN 1 SET STATISTICS 1000;
