-- A club's movie night: on a day, hosted by the member who planned it, watched
-- on some of the members' services, in the country they stream in, or at the
-- cinema, picked from the members' lists or from one list. The club page reads
-- a club's nights, the latest first.
CREATE TABLE movie_night (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    club_id    BIGINT NOT NULL REFERENCES club (id) ON DELETE CASCADE,
    host_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title      VARCHAR NOT NULL,
    night_on   DATE NOT NULL,
    country    VARCHAR(2),
    cinema     BOOLEAN NOT NULL,
    list_id    BIGINT REFERENCES movie_list (id) ON DELETE SET NULL,
    winner_id  VARCHAR REFERENCES movie (id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_movie_night_club_id_night_on ON movie_night (club_id, night_on DESC);

-- The services a night is watched on.
CREATE TABLE movie_night_provider (
    night_id          BIGINT NOT NULL REFERENCES movie_night (id) ON DELETE CASCADE,
    watch_provider_id INTEGER NOT NULL REFERENCES watch_provider (id),
    PRIMARY KEY (night_id, watch_provider_id)
);

-- The movies to vote on, picked when the night was planned, the best first.
CREATE TABLE movie_night_candidate (
    night_id BIGINT NOT NULL REFERENCES movie_night (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    reason   VARCHAR NOT NULL,
    PRIMARY KEY (night_id, movie_id)
);

-- One vote per member, for one of the night's candidates.
CREATE TABLE movie_night_vote (
    night_id BIGINT NOT NULL REFERENCES movie_night (id) ON DELETE CASCADE,
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    voted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (night_id, user_id),
    FOREIGN KEY (night_id, movie_id) REFERENCES movie_night_candidate (night_id, movie_id) ON DELETE CASCADE
);
