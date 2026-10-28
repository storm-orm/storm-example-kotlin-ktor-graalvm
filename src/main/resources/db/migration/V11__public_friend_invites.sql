-- A public invite link, to post where anyone may see it: every visitor who
-- accepts it becomes a friend of its creator. It is not used up and does not
-- expire; making a new one replaces it, which is how it is withdrawn.
ALTER TABLE friend_invite ADD COLUMN reusable BOOLEAN NOT NULL DEFAULT FALSE;
