-- Seeing the same picks too often: each recommendation counts how often it
-- was shown, and its score decays with the count (RecommendationRanker);
-- a pick the visitor opened starts counting afresh. The counts survive a
-- recomputation, for the movies that stay in the list.
ALTER TABLE recommendation ADD COLUMN shown_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE recommendation ADD COLUMN last_shown_at TIMESTAMP;

-- "Not interested": a dismissed movie is never recommended again, and weighs
-- a little against its people, genres and decade, like a mild thumbs down.
CREATE TABLE recommendation_dismissal (
    user_id      VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id     VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    dismissed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);
