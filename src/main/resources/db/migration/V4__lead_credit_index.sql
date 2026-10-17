-- A movie is found by its title or by the names of its leads (the first three
-- billed) and directors. A common fragment such as "rob" matches thousands of
-- people; looking up their credits through this index reads only the credits
-- that count, and holds the movie as well, so the lookup never reads the table.
CREATE INDEX idx_principal_lead_person ON principal (person_id, movie_id)
    WHERE ordering <= 3 OR category = 'director';

ANALYZE principal;
