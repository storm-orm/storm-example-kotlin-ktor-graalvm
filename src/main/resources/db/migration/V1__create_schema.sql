-- Storm IMDB demo schema.
-- Movie data comes from the public IMDB dataset (https://datasets.imdbws.com/),
-- filtered to movies with at least 1000 votes. The movie_view and watchlist
-- tables back the application's recently-viewed and watchlist features.
--
-- PostgreSQL. The unit tests run against H2 with src/test/resources/schema.sql,
-- which mirrors the tables; EntitySchemaValidationTest applies this file to
-- PostgreSQL and checks the entities against it.

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

-- Search matches every word anywhere in a title or name, ignoring case and
-- accents, so "leon" finds Léon and "amelie" finds Amélie. The queries compare
-- LOWER(fold_accents(column)) LIKE LOWER(fold_accents('%word%')), applying the
-- function to the column and to the input alike (see SearchText.kt). A leading
-- wildcard rules out a btree index; trigram GIN indexes on exactly that
-- expression serve it, for words of three characters or more.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- An index takes only an immutable function. unaccent is marked stable because
-- its dictionary can be changed; naming the dictionary and the schema here fixes
-- both, which makes the wrapper immutable in practice.
CREATE FUNCTION fold_accents(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

CREATE INDEX idx_movie_primary_title_fold_trgm ON movie USING gin (LOWER(fold_accents(primary_title)) gin_trgm_ops);
CREATE INDEX idx_movie_original_title_fold_trgm ON movie USING gin (LOWER(fold_accents(original_title)) gin_trgm_ops);
CREATE INDEX idx_person_primary_name_fold_trgm ON person USING gin (LOWER(fold_accents(primary_name)) gin_trgm_ops);

-- The planner estimates how many rows a search word matches from statistics it
-- samples per index expression. The default sample is too coarse for a word that
-- occurs in a handful of the names: it estimates thousands of matches and reads
-- every credit instead of looking up the few people found. A larger sample keeps
-- the estimate near the real count. The statistics themselves are gathered once
-- the import has filled the tables, when autovacuum analyzes them.
ALTER INDEX idx_movie_primary_title_fold_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_movie_original_title_fold_trgm ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_person_primary_name_fold_trgm ALTER COLUMN 1 SET STATISTICS 1000;

-- A movie is found by its title or by the names of its leads (the first three
-- billed) and directors. A common fragment such as "rob" matches thousands of
-- people; looking up their credits through this index reads only the credits
-- that count, and holds the movie as well, so the lookup never reads the table.
CREATE INDEX idx_principal_lead_person ON principal (person_id, movie_id)
    WHERE ordering <= 3 OR category = 'director';
