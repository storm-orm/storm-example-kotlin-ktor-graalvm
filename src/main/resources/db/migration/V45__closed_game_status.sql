-- How a game stood when it closed for a player, and who it was with: the
-- finished list and the archive show them without looking at the game again.
ALTER TABLE game_player ADD COLUMN closed_status VARCHAR;
ALTER TABLE game_player ADD COLUMN closed_with VARCHAR;
