-- The day counts keyed by what they are: the movie and the date, which
-- Storm now accepts as a primary key. The table is empty until the first
-- fold, so it is recreated rather than altered.
DROP TABLE movie_view_day;

CREATE TABLE movie_view_day (
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    view_date  DATE NOT NULL,
    view_count INTEGER NOT NULL,
    PRIMARY KEY (movie_id, view_date)
);

CREATE INDEX idx_movie_view_day_view_date ON movie_view_day (view_date);
