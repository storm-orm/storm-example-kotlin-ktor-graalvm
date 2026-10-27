-- Storm IMDB demo schema.
-- Movie data comes from the public IMDB dataset (https://datasets.imdbws.com/),
-- filtered to movies with at least 1000 votes. The movie_view and watchlist
-- tables back the application's recently-viewed and watchlist features.
--
-- This file is also executed against H2 by @StormTest, so it sticks to DDL
-- that is valid in both PostgreSQL and H2, apart from the generated columns.

-- The searches fold accents through fold_accents, which PostgreSQL defines over
-- unaccent (V1). H2 has no unaccent, so the tests define it in Java.
CREATE ALIAS fold_accents DETERMINISTIC FOR 'st.orm.demo.imdb.H2Functions.foldAccents';

CREATE TABLE movie (
    id              VARCHAR NOT NULL PRIMARY KEY, -- IMDB tconst, e.g. 'tt0133093'
    primary_title   VARCHAR NOT NULL,
    original_title  VARCHAR NOT NULL,
    start_year      INTEGER,
    runtime_minutes INTEGER,
    -- The titles without accents, stored for the searches (V2). H2 stores a
    -- generated column without being told; PostgreSQL is told STORED.
    primary_title_folded  VARCHAR GENERATED ALWAYS AS (fold_accents(primary_title)) NOT NULL,
    original_title_folded VARCHAR GENERATED ALWAYS AS (fold_accents(original_title)) NOT NULL
);

CREATE TABLE genre (
    id   INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR NOT NULL,
    CONSTRAINT uk_genre_name UNIQUE (name)
);

CREATE TABLE movie_genre (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    genre_id INTEGER NOT NULL REFERENCES genre (id),
    PRIMARY KEY (movie_id, genre_id)
);

CREATE TABLE person (
    id           VARCHAR NOT NULL PRIMARY KEY, -- IMDB nconst, e.g. 'nm0000206'
    primary_name VARCHAR NOT NULL,
    birth_year   INTEGER,
    death_year   INTEGER
);

CREATE TABLE principal (
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    ordering   INTEGER NOT NULL, -- IMDB billing order, unique per title
    person_id  VARCHAR NOT NULL REFERENCES person (id),
    category   VARCHAR NOT NULL, -- actor, actress, director, writer, ...
    characters VARCHAR,          -- display-ready character names, e.g. 'Neo'
    PRIMARY KEY (movie_id, ordering)
);

CREATE TABLE rating (
    movie_id       VARCHAR NOT NULL PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    average_rating NUMERIC(3, 1) NOT NULL,
    vote_count     INTEGER NOT NULL
);

-- The movies that joined the catalogue after it was created (V3).
CREATE TABLE movie_addition (
    movie_id VARCHAR NOT NULL PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL
);

CREATE TABLE movie_view (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    viewed_at TIMESTAMP NOT NULL,
    user_id   VARCHAR
);

-- Views older than a month, folded into one row per movie and day (V7).
CREATE TABLE movie_view_day (
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    view_date  DATE NOT NULL,
    view_count INTEGER NOT NULL,
    PRIMARY KEY (movie_id, view_date)
);

-- Personal accounts (V4): a visitor's account, the git provider accounts
-- linked to it, and the two lists that belong to it.
CREATE TABLE app_user (
    id           VARCHAR NOT NULL PRIMARY KEY,
    name         VARCHAR,
    avatar_url   VARCHAR,
    created_at   TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    share_seen   BOOLEAN NOT NULL DEFAULT TRUE,
    share_name   BOOLEAN NOT NULL DEFAULT TRUE,
    share_dates  BOOLEAN NOT NULL DEFAULT TRUE,
    club_share_seen  BOOLEAN NOT NULL DEFAULT TRUE,
    club_share_name  BOOLEAN NOT NULL DEFAULT TRUE,
    club_share_dates BOOLEAN NOT NULL DEFAULT FALSE,
    country          VARCHAR(2),
    movie_filter     VARCHAR,
    level            VARCHAR NOT NULL DEFAULT 'EXTRA'
);

CREATE TABLE user_login (
    provider  VARCHAR NOT NULL,
    subject   VARCHAR NOT NULL,
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    username  VARCHAR NOT NULL,
    linked_at TIMESTAMP NOT NULL,
    PRIMARY KEY (provider, subject)
);

CREATE TABLE watchlist (
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

CREATE TABLE movie_seen (
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    seen_at  TIMESTAMP NOT NULL,
    liked    BOOLEAN,
    PRIMARY KEY (user_id, movie_id)
);

-- Recommendations (V5): a visitor's computed picks with their reasons as JSON,
-- and what became of them.
CREATE TABLE recommendation (
    user_id       VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id      VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    score         NUMERIC(8, 4) NOT NULL,
    seed_movie_id VARCHAR REFERENCES movie (id) ON DELETE SET NULL,
    reasons       VARCHAR NOT NULL,
    computed_at   TIMESTAMP NOT NULL,
    shown_count   INTEGER NOT NULL DEFAULT 0,
    last_shown_at TIMESTAMP,
    PRIMARY KEY (user_id, movie_id)
);

CREATE TABLE recommendation_dismissal (
    user_id      VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id     VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    dismissed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

CREATE TABLE recommendation_event (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    kind     VARCHAR NOT NULL,
    at       TIMESTAMP NOT NULL
);

-- Photo galleries from the person's Wikipedia article, fetched from Wikimedia
-- on first view and stored here for every request after that. The photos
-- column holds the gallery as a JSON document, typed VARCHAR to keep the DDL
-- valid for both PostgreSQL and H2.
CREATE TABLE person_gallery (
    person_id  VARCHAR NOT NULL PRIMARY KEY REFERENCES person (id) ON DELETE CASCADE,
    photos     VARCHAR NOT NULL,
    fetched_at TIMESTAMP NOT NULL
);

-- Search and navigation indexes.
CREATE INDEX idx_movie_primary_title ON movie (primary_title);
CREATE INDEX idx_person_primary_name ON person (primary_name);
CREATE INDEX idx_movie_genre_genre_id ON movie_genre (genre_id);
CREATE INDEX idx_principal_person_id ON principal (person_id);
CREATE INDEX idx_rating_average_rating ON rating (average_rating DESC);
CREATE INDEX idx_movie_view_viewed_at ON movie_view (viewed_at DESC);
CREATE INDEX idx_movie_view_day_view_date ON movie_view_day (view_date);
CREATE INDEX idx_user_login_user_id ON user_login (user_id);
CREATE INDEX idx_watchlist_user_id_added_at ON watchlist (user_id, added_at DESC);
CREATE INDEX idx_movie_seen_user_id_seen_at ON movie_seen (user_id, seen_at DESC);
CREATE INDEX idx_recommendation_user_id_score ON recommendation (user_id, score DESC, movie_id);
CREATE INDEX idx_recommendation_event_kind ON recommendation_event (kind);

-- V9: the account of a returning visitor's view (H2 has no partial indexes).
ALTER TABLE movie_view ADD CONSTRAINT fk_movie_view_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL;
CREATE INDEX idx_movie_view_user_viewed_at ON movie_view (user_id, viewed_at DESC);

-- V10: friends.
CREATE TABLE friendship (
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    friend_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    since     TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, friend_id),
    CHECK (user_id <> friend_id)
);
CREATE INDEX idx_friendship_friend_id ON friendship (friend_id);

-- V12: the catch-up page reads the most voted movies first.
CREATE INDEX idx_rating_vote_count ON rating (vote_count DESC, movie_id);

-- V13: check-ins, one row per watch.
CREATE TABLE check_in (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    watched_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_check_in_user_id_watched_at ON check_in (user_id, watched_at DESC, id DESC);
CREATE INDEX idx_check_in_user_id_movie_id ON check_in (user_id, movie_id);

-- V14: clubs.
CREATE TABLE club (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE club_member (
    club_id     BIGINT NOT NULL REFERENCES club (id) ON DELETE CASCADE,
    user_id     VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    admin       BOOLEAN NOT NULL,
    joined_at   TIMESTAMP NOT NULL,
    share_seen  BOOLEAN,
    share_name  BOOLEAN,
    share_dates BOOLEAN,
    PRIMARY KEY (club_id, user_id)
);
CREATE INDEX idx_club_member_user_id ON club_member (user_id);
-- The friend invites of V10 and V11 became the invites of both kinds.
CREATE TABLE invite (
    token      VARCHAR NOT NULL PRIMARY KEY,
    kind       VARCHAR NOT NULL,
    inviter_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    reusable   BOOLEAN NOT NULL DEFAULT FALSE,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    club_id    BIGINT REFERENCES club (id) ON DELETE CASCADE,
    CHECK ((kind = 'club') = (club_id IS NOT NULL))
);
CREATE INDEX idx_invite_inviter_id ON invite (inviter_id);
CREATE INDEX idx_invite_club_id ON invite (club_id);

-- V15: friend requests between club members.
CREATE TABLE friend_request (
    requester_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    recipient_id VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    club_id      BIGINT NOT NULL REFERENCES club (id) ON DELETE CASCADE,
    created_at   TIMESTAMP NOT NULL,
    dismissed    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (requester_id, recipient_id),
    CHECK (requester_id <> recipient_id)
);
CREATE INDEX idx_friend_request_recipient_id ON friend_request (recipient_id);
CREATE INDEX idx_friend_request_club_id ON friend_request (club_id);

-- V16: where a movie is known, every source in one table.
CREATE TABLE movie_source (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        VARCHAR NOT NULL,
    movie_id    VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    external_id VARCHAR,
    trailer_key VARCHAR,
    watch_providers VARCHAR,
    fetched_at  TIMESTAMP,
    credits     VARCHAR,
    UNIQUE (movie_id, kind),
    CHECK ((kind = 'imdb') = (fetched_at IS NULL))
);
CREATE INDEX idx_movie_source_kind_external_id ON movie_source (kind, external_id);
CREATE INDEX idx_movie_source_fetched_at ON movie_source (fetched_at);

CREATE TABLE watch_provider (
    id        INTEGER PRIMARY KEY,
    name      VARCHAR NOT NULL,
    logo_path VARCHAR
);

CREATE TABLE user_watch_provider (
    user_id           VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    watch_provider_id INTEGER NOT NULL REFERENCES watch_provider (id),
    PRIMARY KEY (user_id, watch_provider_id)
);

CREATE TABLE movie_language (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    language VARCHAR NOT NULL,
    PRIMARY KEY (movie_id, language)
);
CREATE INDEX idx_movie_language_language ON movie_language (language);

CREATE TABLE movie_country (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    country  VARCHAR(2) NOT NULL,
    PRIMARY KEY (movie_id, country)
);
CREATE INDEX idx_movie_country_country ON movie_country (country);

CREATE TABLE movie_tag (
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    tag      VARCHAR NOT NULL,
    PRIMARY KEY (movie_id, tag)
);

-- V26: a year in movies shared with a link.
CREATE TABLE year_share (
    token      VARCHAR PRIMARY KEY,
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    of_year    INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (user_id, of_year)
);

-- V27: curated lists of movies.
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

CREATE TABLE movie_list_item (
    list_id     BIGINT NOT NULL REFERENCES movie_list (id) ON DELETE CASCADE,
    movie_id    VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    added_by_id VARCHAR REFERENCES app_user (id) ON DELETE SET NULL,
    added_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (list_id, movie_id)
);
CREATE INDEX idx_movie_list_item_movie_id ON movie_list_item (movie_id);

-- V28: movie nights.
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
    created_at TIMESTAMP NOT NULL,
    method     VARCHAR NOT NULL DEFAULT 'VOTE'
);
CREATE INDEX idx_movie_night_club_id_night_on ON movie_night (club_id, night_on DESC);
CREATE TABLE movie_night_provider (
    night_id          BIGINT NOT NULL REFERENCES movie_night (id) ON DELETE CASCADE,
    watch_provider_id INTEGER NOT NULL REFERENCES watch_provider (id),
    PRIMARY KEY (night_id, watch_provider_id)
);
CREATE TABLE movie_night_candidate (
    night_id BIGINT NOT NULL REFERENCES movie_night (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    reason   VARCHAR NOT NULL,
    PRIMARY KEY (night_id, movie_id)
);
CREATE TABLE movie_night_vote (
    night_id BIGINT NOT NULL REFERENCES movie_night (id) ON DELETE CASCADE,
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    voted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (night_id, user_id, movie_id),
    FOREIGN KEY (night_id, movie_id) REFERENCES movie_night_candidate (night_id, movie_id) ON DELETE CASCADE
);

-- V29: the daily quiz.
CREATE TABLE quiz_day (
    quiz_on    DATE NOT NULL,
    filter_key VARCHAR NOT NULL,
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    PRIMARY KEY (quiz_on, filter_key)
);
CREATE TABLE quiz_play (
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    quiz_on    DATE NOT NULL,
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    guesses    VARCHAR NOT NULL,
    solved     BOOLEAN NOT NULL,
    finished   BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, quiz_on)
);
CREATE INDEX idx_quiz_play_quiz_on ON quiz_play (quiz_on);

-- V30: games played as matches.
CREATE TABLE game_match (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game       VARCHAR NOT NULL,
    mode       VARCHAR NOT NULL,
    club_id    BIGINT REFERENCES club (id) ON DELETE CASCADE,
    host_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    closes_at  TIMESTAMP,
    ended_at   TIMESTAMP,
    pool       VARCHAR,
    pool_size  INTEGER,
    pool_fallback BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((mode = 'CLUB') = (club_id IS NOT NULL))
);
CREATE INDEX idx_game_match_club_id ON game_match (club_id, created_at DESC);
CREATE INDEX idx_game_match_host_id ON game_match (host_id, created_at DESC);
CREATE TABLE game_player (
    match_id   BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    turn_order INTEGER NOT NULL,
    score      INTEGER NOT NULL,
    finished   BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    hints      INTEGER NOT NULL DEFAULT 0,
    hint_at    INTEGER,
    hint_shown INTEGER NOT NULL DEFAULT 0,
    closed_at  TIMESTAMP,
    pool_seen  INTEGER,
    PRIMARY KEY (match_id, user_id)
);
CREATE INDEX idx_game_player_user_id ON game_player (user_id);

-- V43: a night chosen by swiping has the club's round on its candidates.
ALTER TABLE movie_night ADD COLUMN swipe_match_id BIGINT REFERENCES game_match (id) ON DELETE SET NULL;
CREATE INDEX idx_game_player_user_closed ON game_player (user_id, closed_at);
CREATE TABLE game_card (
    match_id BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    rating   NUMERIC(3, 1) NOT NULL,
    PRIMARY KEY (match_id, position)
);
CREATE TABLE chain_link (
    match_id  BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    player_id VARCHAR REFERENCES app_user (id) ON DELETE SET NULL,
    via_id    VARCHAR REFERENCES person (id),
    points    INTEGER NOT NULL,
    added_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, position)
);

-- V33: Movie Draft.
CREATE TABLE draft_theme (
    match_id BIGINT PRIMARY KEY REFERENCES game_match (id) ON DELETE CASCADE,
    genre_id INTEGER NOT NULL REFERENCES genre (id),
    decade   INTEGER NOT NULL
);
CREATE TABLE draft_pick (
    match_id  BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    player_id VARCHAR REFERENCES app_user (id) ON DELETE SET NULL,
    auto      BOOLEAN NOT NULL,
    picked_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, position),
    UNIQUE (match_id, movie_id)
);

-- V35: swipes and swipe rounds.
CREATE TABLE swipe (
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    way        VARCHAR NOT NULL,
    swiped_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);
CREATE INDEX idx_swipe_movie_id ON swipe (movie_id);
CREATE TABLE swipe_round_card (
    match_id BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    PRIMARY KEY (match_id, position)
);
CREATE TABLE swipe_round_swipe (
    match_id  BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    user_id   VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    movie_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    way       VARCHAR NOT NULL,
    swiped_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, user_id, movie_id)
);

-- V36: Connect.
CREATE TABLE connect_puzzle (
    match_id   BIGINT PRIMARY KEY REFERENCES game_match (id) ON DELETE CASCADE,
    start_id   VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    target_id  VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    par        INTEGER NOT NULL,
    path       VARCHAR NOT NULL,
    hints      INTEGER NOT NULL DEFAULT 0,
    undos      INTEGER NOT NULL DEFAULT 0,
    hint_at    INTEGER,
    hint_shown INTEGER NOT NULL DEFAULT 0
);

-- V37: the daily puzzles.
CREATE TABLE daily_puzzle (
    puzzle_on  DATE NOT NULL,
    game       VARCHAR NOT NULL,
    filter_key VARCHAR NOT NULL,
    data       VARCHAR NOT NULL,
    PRIMARY KEY (puzzle_on, game, filter_key)
);
CREATE TABLE daily_play (
    user_id    VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    puzzle_on  DATE NOT NULL,
    game       VARCHAR NOT NULL,
    filter_key VARCHAR NOT NULL,
    answers    VARCHAR NOT NULL,
    score      INTEGER NOT NULL,
    mistakes   INTEGER NOT NULL,
    finished   BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, puzzle_on, game)
);
CREATE INDEX idx_daily_play_day_game ON daily_play (puzzle_on, game);

-- Name That Cast: the movie the house picked for a match.
CREATE TABLE cast_round (
    match_id BIGINT PRIMARY KEY REFERENCES game_match (id) ON DELETE CASCADE,
    movie_id VARCHAR NOT NULL REFERENCES movie (id) ON DELETE CASCADE
);

-- How many of the cast each player says they can name: the highest names them.
CREATE TABLE cast_bid (
    match_id BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    user_id  VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    count    INTEGER NOT NULL,
    bid_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, user_id)
);

-- The names given, in their order: the credit each one matched, or none when it was wrong.
CREATE TABLE cast_answer (
    match_id    BIGINT NOT NULL REFERENCES game_match (id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    user_id     VARCHAR NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    answer      VARCHAR NOT NULL,
    credit      VARCHAR,
    answered_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id, position)
);
