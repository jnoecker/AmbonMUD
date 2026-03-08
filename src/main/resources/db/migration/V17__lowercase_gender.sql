UPDATE players SET gender = LOWER(gender);
ALTER TABLE players ALTER COLUMN gender SET DEFAULT 'enby';
