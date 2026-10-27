-- The levels start over, to be earned by the rites as well as the points:
-- every level is looked at again the next time its user is, and raised as
-- far as their points, badges and invites reach. A one-off, for the rites
-- that came in today; levels earned later are kept whatever the rules do.
UPDATE app_user SET level = 'EXTRA';
