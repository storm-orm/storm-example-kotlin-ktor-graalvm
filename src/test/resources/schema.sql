-- Storm IMDB demo schema.
-- Movie data comes from the public IMDB dataset (https://datasets.imdbws.com/),
-- filtered to movies with at least 1000 votes. The movie_view and watchlist
-- tables back the application's recently-viewed and watchlist features.
--
-- This file is also executed against H2 by @StormTest, so it sticks to DDL
-- that is valid in both PostgreSQL and H2.

CREATE TABLE movie (
    id              VARCHAR NOT NULL PRIMARY KEY, -- IMDB tconst, e.g. 'tt0133093'
    primary_title   VARCHAR NOT NULL,
    original_title  VARCHAR NOT NULL,
    start_year      INTEGER,
    runtime_minutes INTEGER
);

CREATE TABLE genre (
    id   INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR NOT NULL,
    CONSTRAINT uk_genre_name UNIQUE (name)
);

CREATE TABLE movie_genre (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    genre_id INTEGER NOT NULL REFERENCES genre (id),
    PRIMARY KEY (movie_id, genre_id)
);

CREATE TABLE person (
    id           VARCHAR NOT NULL PRIMARY KEY, -- IMDB nconst, e.g. 'nm0000206'
    primary_name VARCHAR NOT NULL,
    birth_year   INTEGER,
    death_year   INTEGER
);

CREATE TABLE principal (
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    ordering   INTEGER NOT NULL, -- IMDB billing order, unique per title
    person_id  VARCHAR NOT NULL REFERENCES person (id),
    category   VARCHAR NOT NULL, -- actor, actress, director, writer, ...
    characters VARCHAR,          -- display-ready character names, e.g. 'Neo'
    PRIMARY KEY (movie_id, ordering)
);

CREATE TABLE rating (
    movie_id       VARCHAR NOT NULL PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    average_rating NUMERIC(3, 1) NOT NULL,
    vote_count     INTEGER NOT NULL
);

CREATE TABLE movie_view (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    viewed_at TIMESTAMP NOT NULL
);

CREATE TABLE watchlist (
    movie_id VARCHAR NOT NULL PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL
);

-- Photo galleries from the person's Wikipedia article, fetched from Wikimedia
-- on first view and stored here for every request after that. The photos
-- column holds the gallery as a JSON document, typed VARCHAR to keep the DDL
-- valid for both PostgreSQL and H2.
CREATE TABLE person_gallery (
    person_id  VARCHAR NOT NULL PRIMARY KEY REFERENCES person (id) ON DELETE CASCADE,
    photos     VARCHAR NOT NULL,
    fetched_at TIMESTAMP NOT NULL
);

-- Search and navigation indexes.
CREATE INDEX idx_movie_primary_title ON movie (primary_title);
CREATE INDEX idx_person_primary_name ON person (primary_name);
CREATE INDEX idx_movie_genre_genre_id ON movie_genre (genre_id);
CREATE INDEX idx_principal_person_id ON principal (person_id);
CREATE INDEX idx_rating_average_rating ON rating (average_rating DESC);
CREATE INDEX idx_movie_view_viewed_at ON movie_view (viewed_at DESC);
