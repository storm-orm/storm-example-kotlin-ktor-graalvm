-- The planner estimates how many rows a search word matches from statistics it
-- samples per index expression. The default sample is too coarse for a word that
-- occurs in a handful of 330k names: it estimated thousands of matches, and joined
-- them to the credits by reading the whole principal table instead of looking up
-- the few people found. A larger sample keeps the estimate near the real count.
ALTER INDEX idx_movie_primary_title_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_movie_original_title_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_person_primary_name_trgm ALTER COLUMN 1 SET STATISTICS 1000;

-- Expression statistics exist only once the table is analyzed; until then the
-- planner prefers reading every row over the indexes created in V2.
ANALYZE movie;
ANALYZE person;
