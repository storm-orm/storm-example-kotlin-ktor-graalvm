-- Storm IMDB demo schema.
-- Movie data comes from the public IMDB dataset (https://datasets.imdbws.com/),
-- filtered to movies with at least 1000 votes. The movie_view and watchlist
-- tables back the application's recently-viewed and watchlist features.
--
-- This file is also executed against H2 by @StormTest, so it sticks to DDL
-- that is valid in both PostgreSQL and H2, apart from the generated columns.

-- The searches fold accents through fold_accents, which PostgreSQL defines over
-- unaccent (V1). H2 has no unaccent, so the tests define it in Java.
CREATE ALIAS fold_accents DETERMINISTIC FOR 'st.orm.demo.imdb.H2Functions.foldAccents';

CREATE TABLE movie (
    id              VARCHAR NOT NULL PRIMARY KEY, -- IMDB tconst, e.g. 'tt0133093'
    primary_title   VARCHAR NOT NULL,
    original_title  VARCHAR NOT NULL,
    start_year      INTEGER,
    runtime_minutes INTEGER,
    -- The titles without accents, stored for the searches (V2). H2 stores a
    -- generated column without being told; PostgreSQL is told STORED.
    primary_title_folded  VARCHAR GENERATED ALWAYS AS (fold_accents(primary_title)) NOT NULL,
    original_title_folded VARCHAR GENERATED ALWAYS AS (fold_accents(original_title)) NOT NULL
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

-- The movies that joined the catalogue after it was created (V3).
CREATE TABLE movie_addition (
    movie_id VARCHAR NOT NULL PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL
);

CREATE TABLE movie_view (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    viewed_at TIMESTAMP NOT NULL
);

-- Views older than a month, folded into one row per movie and day (V7).
CREATE TABLE movie_view_day (
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    view_date  DATE NOT NULL,
    view_count INTEGER NOT NULL,
    PRIMARY KEY (movie_id, view_date)
);

-- Personal accounts (V4): a visitor's account, the git provider accounts
-- linked to it, and the two lists that belong to it.
CREATE TABLE app_user (
    id           VARCHAR NOT NULL PRIMARY KEY,
    name         VARCHAR,
    avatar_url   VARCHAR,
    created_at   TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL
);

CREATE TABLE user_login (
    provider  VARCHAR NOT NULL,
    subject   VARCHAR NOT NULL,
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    username  VARCHAR NOT NULL,
    linked_at TIMESTAMP NOT NULL,
    PRIMARY KEY (provider, subject)
);

CREATE TABLE watchlist (
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

CREATE TABLE movie_seen (
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    seen_at  TIMESTAMP NOT NULL,
    liked    BOOLEAN,
    PRIMARY KEY (user_id, movie_id)
);

-- Recommendations (V5): a visitor's computed picks with their reasons as JSON,
-- and what became of them.
CREATE TABLE recommendation (
    user_id       VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id      VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    score         NUMERIC(8, 4) NOT NULL,
    seed_movie_id VARCHAR REFERENCES movie (id) ON DELETE SET NULL,
    reasons       VARCHAR NOT NULL,
    computed_at   TIMESTAMP NOT NULL,
    shown_count   INTEGER NOT NULL DEFAULT 0,
    last_shown_at TIMESTAMP,
    PRIMARY KEY (user_id, movie_id)
);

CREATE TABLE recommendation_dismissal (
    user_id      VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id     VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    dismissed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

CREATE TABLE recommendation_event (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    kind     VARCHAR NOT NULL,
    at       TIMESTAMP NOT NULL
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
CREATE INDEX idx_movie_view_day_view_date ON movie_view_day (view_date);
CREATE INDEX idx_user_login_user_id ON user_login (user_id);
CREATE INDEX idx_watchlist_user_id_added_at ON watchlist (user_id, added_at DESC);
CREATE INDEX idx_movie_seen_user_id_seen_at ON movie_seen (user_id, seen_at DESC);
CREATE INDEX idx_recommendation_user_id_score ON recommendation (user_id, score DESC, movie_id);
CREATE INDEX idx_recommendation_event_kind ON recommendation_event (kind);
