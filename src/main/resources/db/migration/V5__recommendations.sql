-- Recommendations: what to watch next, per visitor.
--
-- A thumbs up or down on a seen movie sharpens the visitor's taste; no vote
-- is the common case and stays NULL.
ALTER TABLE movie_seen ADD COLUMN liked BOOLEAN;

-- The recommendations of a visitor, computed in the background whenever the
-- visitor's lists change (RecommendationService) and read by the pages. The
-- seed is the movie of the visitor's own that argued most for the pick, for
-- the "Because you saw" shelves; the reasons are shown on the card, as a
-- JSON document typed VARCHAR to keep the DDL valid for PostgreSQL and H2.
CREATE TABLE recommendation (
    user_id       VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id      VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    score         NUMERIC(8, 4) NOT NULL,
    seed_movie_id VARCHAR REFERENCES movie (id) ON DELETE SET NULL,
    reasons       VARCHAR NOT NULL,
    computed_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

-- The page scrolls a visitor's recommendations by score, then movie id.
CREATE INDEX idx_recommendation_user_id_score ON recommendation (user_id, score DESC, movie_id);

-- What happens to a recommendation: the visitor opened it from a
-- recommendation surface, saved it to the watchlist, or marked it seen.
CREATE TABLE recommendation_event (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    kind     VARCHAR NOT NULL,
    at       TIMESTAMP NOT NULL
);

CREATE INDEX idx_recommendation_event_kind ON recommendation_event (kind);
