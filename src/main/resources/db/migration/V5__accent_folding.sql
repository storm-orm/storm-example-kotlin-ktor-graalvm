-- Search ignores accents, so "leon" finds Léon and "amelie" finds Amélie. Whether
-- accents count is decided by the column's collation, and PostgreSQL's collations
-- compare them. Each searched column therefore gets a generated copy without its
-- accents (V6, V7), which the queries search instead of the original; the application
-- removes the accents from the input the same way (see SearchText.kt).
--
-- The work is split over V5 to V9 because a migration runs in one transaction while
-- Flyway holds its lock on the schema history table in another, which sits idle until
-- the migration ends, and the platform ends a transaction idle for 60 seconds. Each
-- migration stays well within that.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- A generated column takes only an immutable function. unaccent is marked stable
-- because its dictionary can be changed; naming the dictionary and the schema here
-- fixes both, which makes the wrapper immutable in practice.
CREATE FUNCTION fold_accents(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);
