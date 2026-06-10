-- Akathavae pledge (explorer path): pledge state + the player's Arcanum journal.
ALTER TABLE players ADD COLUMN is_akathavae BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE players ADD COLUMN akathavae_pledged_at_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN akathavae_renounced_at_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN arcanum_data TEXT NOT NULL DEFAULT '{}';
