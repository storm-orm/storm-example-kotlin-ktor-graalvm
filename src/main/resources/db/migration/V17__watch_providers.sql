-- Where a movie plays in the visitor's country: TMDB has it from JustWatch, per
-- country, as the platforms that stream it or show it free.

-- The platforms, by TMDB's id for them, with the path of their logo on TMDB's
-- image server.
CREATE TABLE watch_provider (
    id        INTEGER PRIMARY KEY,
    name      VARCHAR NOT NULL,
    logo_path VARCHAR
);

-- TMDB: per country, the ids of the platforms the movie plays on, as JSON,
-- asked along with the trailer. NULL where TMDB was asked before the platforms were.
ALTER TABLE movie_source ADD COLUMN watch_providers VARCHAR;

-- The country a user is from (ISO 3166-1 alpha-2), for the platforms; NULL
-- means the browser's language says it.
ALTER TABLE app_user ADD COLUMN country VARCHAR(2);
