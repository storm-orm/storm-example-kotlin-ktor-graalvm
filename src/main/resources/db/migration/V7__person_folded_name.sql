-- The name without its accents, which the searches read (see V5). The old trigram
-- index goes first, so the rewrite of the table does not rebuild it; V8 indexes the
-- new column.
DROP INDEX idx_person_primary_name_trgm;

ALTER TABLE person
    ADD COLUMN primary_name_folded TEXT GENERATED ALWAYS AS (fold_accents(primary_name)) STORED;
