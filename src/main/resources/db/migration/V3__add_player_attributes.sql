-- Add primary attribute columns
ALTER TABLE players
    ADD COLUMN strength     INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN dexterity    INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN intelligence INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN wisdom       INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN charisma     INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN race         VARCHAR(32) NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN player_class VARCHAR(32) NOT NULL DEFAULT 'ADVENTURER';

-- Migrate existing players: constitution 0 -> 10
UPDATE players SET constitution = 10 WHERE constitution = 0;

-- Update default for constitution column going forward
ALTER TABLE players ALTER COLUMN constitution SET DEFAULT 10;
