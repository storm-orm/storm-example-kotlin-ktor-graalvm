-- How far a user has come as a watcher, the highest level their watched movies
-- reached: kept once reached, raised whenever it is looked at.
ALTER TABLE app_user ADD COLUMN level VARCHAR NOT NULL DEFAULT 'EXTRA';
