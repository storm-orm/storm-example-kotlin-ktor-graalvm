-- The view log grows by every visit, a few hundred thousand rows a week once
-- crawlers come by, while only the last week matters for what is trending
-- and the last fifty views for the home page. A nightly job folds the views
-- older than a month into one row per movie and day (ViewLogService) and
-- removes them, so movie_view holds a month and its indexes stay small,
-- and the history survives as counts.
CREATE TABLE movie_view_day (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    view_date  DATE NOT NULL,
    view_count INTEGER NOT NULL,
    UNIQUE (movie_id, view_date)
);

CREATE INDEX idx_movie_view_day_view_date ON movie_view_day (view_date);
