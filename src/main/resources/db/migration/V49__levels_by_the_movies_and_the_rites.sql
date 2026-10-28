-- The score is the movies watched, a check-in counting as watched, and the
-- check-ins are rites of their own now: the levels start over once more, to
-- be earned by the movies and the rites the next time each user is looked at.
UPDATE app_user SET level = 'EXTRA';
