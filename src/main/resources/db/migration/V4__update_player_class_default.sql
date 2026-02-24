-- Change player_class column default from ADVENTURER to WARRIOR.
-- Existing ADVENTURER players are migrated to WARRIOR.
ALTER TABLE players ALTER COLUMN player_class SET DEFAULT 'WARRIOR';
UPDATE players SET player_class = 'WARRIOR' WHERE player_class = 'ADVENTURER';
