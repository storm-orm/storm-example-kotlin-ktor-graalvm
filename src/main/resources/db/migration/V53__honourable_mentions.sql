-- The honourable mentions: the movies below the top ten, in no order, where
-- one goes when it is taken out of a full ten to make room.
ALTER TABLE top_ten ADD COLUMN mention BOOLEAN NOT NULL DEFAULT FALSE;
