-- The trigram index of V2 on the folded name, with the statistics sample of V3. On
-- the platform the index takes about 20 seconds to build, near the 30-second limit on
-- a statement, so this migration's transaction allows its statements longer.
SET LOCAL statement_timeout = '50s';

CREATE INDEX idx_person_primary_name_folded_trgm ON person USING gin (LOWER(primary_name_folded) gin_trgm_ops);

ALTER INDEX idx_person_primary_name_folded_trgm ALTER COLUMN 1 SET STATISTICS 1000;
