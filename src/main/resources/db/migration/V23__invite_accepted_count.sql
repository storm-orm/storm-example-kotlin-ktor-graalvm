-- How often a public invite link was accepted, for the one who shares it to
-- see; a personal link goes when it is accepted, so its count stays 0.
ALTER TABLE invite ADD COLUMN accepted_count INTEGER NOT NULL DEFAULT 0;
