-- The hints a player took in a game, a Movie Chain's actors shown one at a
-- time: how many in all, and for which link how many of its actors were shown.
ALTER TABLE game_player ADD COLUMN hints INTEGER NOT NULL DEFAULT 0;
ALTER TABLE game_player ADD COLUMN hint_at INTEGER;
ALTER TABLE game_player ADD COLUMN hint_shown INTEGER NOT NULL DEFAULT 0;
