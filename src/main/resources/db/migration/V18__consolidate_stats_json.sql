-- Consolidate the 6 individual stat columns into a single stats_json TEXT column.
-- Migrates existing data by building the JSON map from the individual columns.

ALTER TABLE players ADD COLUMN stats_json TEXT NOT NULL DEFAULT '{}';

UPDATE players SET stats_json =
    '{"STR":' || strength ||
    ',"DEX":' || dexterity ||
    ',"CON":' || constitution ||
    ',"INT":' || intelligence ||
    ',"WIS":' || wisdom ||
    ',"CHA":' || charisma || '}';

ALTER TABLE players DROP COLUMN strength;
ALTER TABLE players DROP COLUMN dexterity;
ALTER TABLE players DROP COLUMN constitution;
ALTER TABLE players DROP COLUMN intelligence;
ALTER TABLE players DROP COLUMN wisdom;
ALTER TABLE players DROP COLUMN charisma;
