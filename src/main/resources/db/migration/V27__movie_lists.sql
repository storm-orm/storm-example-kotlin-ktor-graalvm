-- Lists of movies someone curated: a user's own, public, or a club's, for
-- its members, with the club. The lists page reads a user's lists, a club's,
-- and the others' most recently changed first.
CREATE TABLE movie_list (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR NOT NULL,
    description VARCHAR,
    owner_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    club_id     BIGINT REFERENCES club (id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL
);
CREATE INDEX idx_movie_list_owner_id ON movie_list (owner_id);
CREATE INDEX idx_movie_list_club_id ON movie_list (club_id);
CREATE INDEX idx_movie_list_updated_at ON movie_list (updated_at DESC);

-- A movie is on a list once; who added it stays known while their account does.
CREATE TABLE movie_list_item (
    list_id     BIGINT NOT NULL REFERENCES movie_list (id) ON DELETE CASCADE,
    movie_id    VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    added_by_id VARCHAR REFERENCES app_user (id) ON DELETE SET NULL,
    added_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (list_id, movie_id)
);
CREATE INDEX idx_movie_list_item_movie_id ON movie_list_item (movie_id);
