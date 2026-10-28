-- The countries a movie was made in, as Wikidata names them (ISO 3166-1
-- alpha-2), beside its languages: the recommendations weigh both.
CREATE TABLE movie_country (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    country  VARCHAR(2) NOT NULL,
    PRIMARY KEY (movie_id, country)
);
CREATE INDEX idx_movie_country_country ON movie_country (country);
