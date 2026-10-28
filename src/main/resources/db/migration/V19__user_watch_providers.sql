-- The streaming services a user has, ticked on the account page: For You shows
-- their logos on the posters of the picks they stream, and can keep to them.
CREATE TABLE user_watch_provider (
    user_id           VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    watch_provider_id INTEGER NOT NULL REFERENCES watch_provider (id),
    PRIMARY KEY (user_id, watch_provider_id)
);
