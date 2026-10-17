-- The searches read the expression indexes of V10 and V11, and no release reads
-- the folded columns of V6 and V7 any more. Dropping a column drops its index and
-- leaves the rows in place, so this takes no table rewrite; the space the values
-- held is reclaimed as the rows are next written or vacuumed.
ALTER TABLE movie
    DROP COLUMN primary_title_folded,
    DROP COLUMN original_title_folded;

ALTER TABLE person DROP COLUMN primary_name_folded;
