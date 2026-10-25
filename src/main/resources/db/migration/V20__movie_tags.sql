-- Wikidata, a third source, asked about every movie in the background: it
-- tells where a movie was made and in what language, which become the
-- movie's languages and its tags (Hollywood, Bollywood). Its rows know when
-- they were asked, like TMDB's; only IMDb's do not. Two IMDb ids may lead to
-- one Wikidata item, as IMDb merges duplicates, so its ids are not unique.
ALTER TABLE movie_source DROP CONSTRAINT movie_source_check;
ALTER TABLE movie_source ADD CONSTRAINT movie_source_check CHECK ((kind = 'imdb') = (fetched_at IS NULL));
DROP INDEX idx_movie_source_kind_external_id;
CREATE UNIQUE INDEX idx_movie_source_kind_external_id ON movie_source (kind, external_id) WHERE kind <> 'wikidata';

-- The languages a movie was originally made in, as ISO 639 codes.
CREATE TABLE movie_language (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    language VARCHAR NOT NULL,
    PRIMARY KEY (movie_id, language)
);
CREATE INDEX idx_movie_language_language ON movie_language (language);

-- The tags of a movie, by name.
CREATE TABLE movie_tag (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    tag      VARCHAR NOT NULL,
    PRIMARY KEY (movie_id, tag)
);

-- The movies a user wants picked for them, as JSON; NULL lets every movie through.
ALTER TABLE app_user ADD COLUMN movie_filter VARCHAR;
