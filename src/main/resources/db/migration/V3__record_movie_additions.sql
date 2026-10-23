-- The movies that joined the catalogue after it was created, with the moment
-- each one did, for the recently added page. The initial import is no addition:
-- the catalogue started with those movies, so they have no row here, and the
-- page lists what the daily syncs have added since.
CREATE TABLE movie_addition (
    movie_id VARCHAR NOT NULL PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL
);

-- The page scrolls the additions newest moment first, the most voted first
-- within a moment; the index finds a moment's additions.
CREATE INDEX idx_movie_addition_added_at ON movie_addition (added_at DESC, movie_id);
