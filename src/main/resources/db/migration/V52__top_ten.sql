-- A user's top ten: the movies they rate above all, in their order.
CREATE TABLE top_ten (
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    added_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);
