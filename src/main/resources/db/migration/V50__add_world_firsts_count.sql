-- Akathavae: count of permanent Arcanum world-firsts credited to the player,
-- feeding the world_first achievement criterion (the registry itself is keyed
-- by subject, not player, so the count is persisted per-player here).
ALTER TABLE players ADD COLUMN world_firsts_count INTEGER NOT NULL DEFAULT 0;
