-- The platforms are stored without the services resold through another (an
-- "Amazon Channel") and the plans with ads from now on: forget the ones stored
-- before, so a movie page asks TMDB again.
UPDATE movie_source SET watch_providers = NULL WHERE kind = 'tmdb';
