-- A year in movies its user shared: anyone with the link's token sees that
-- year. A user shares a year once, and the link stays the same.
CREATE TABLE year_share (
    token      VARCHAR PRIMARY KEY,
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    of_year    INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (user_id, of_year)
);
