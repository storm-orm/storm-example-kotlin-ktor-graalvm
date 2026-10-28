-- Where a movie is known: one row per source, told apart by its kind, so IMDb
-- and TMDB sit in the same place and another source is another kind. Each
-- names the movie's id at the source (external_id, NULL where the source was
-- asked and has no such movie); what one source gives besides is a column of
-- its own, NULL in the rows of the others.
CREATE TABLE movie_source (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        VARCHAR NOT NULL,
    movie_id    VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    external_id VARCHAR,
    -- TMDB: the trailer's YouTube key, and when TMDB was asked, as its terms
    -- let what it returns be kept six months at most.
    trailer_key VARCHAR,
    fetched_at  TIMESTAMP,
    UNIQUE (movie_id, kind),
    CHECK ((kind = 'tmdb') = (fetched_at IS NOT NULL))
);

CREATE UNIQUE INDEX idx_movie_source_kind_external_id ON movie_source (kind, external_id);
CREATE INDEX idx_movie_source_fetched_at ON movie_source (fetched_at);

-- The catalogue is synced from IMDb's datasets, so every movie is known there,
-- by the id that is also its own.
INSERT INTO movie_source (kind, movie_id, external_id)
SELECT 'imdb', id, id FROM movie;
