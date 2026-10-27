-- The badges users earned, and when: kept whatever happens to what earned them.
CREATE TABLE user_badge (
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    badge     VARCHAR NOT NULL,
    earned_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, badge)
);
