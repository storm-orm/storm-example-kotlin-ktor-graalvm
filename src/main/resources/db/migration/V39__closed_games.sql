-- When a game closed for a player: it ended, or they closed it. A closed game
-- leaves the Play page's list of open games for the archive.
ALTER TABLE game_player ADD COLUMN closed_at TIMESTAMP;
CREATE INDEX idx_game_player_user_closed ON game_player (user_id, closed_at);
