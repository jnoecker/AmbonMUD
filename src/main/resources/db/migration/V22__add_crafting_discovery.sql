ALTER TABLE players ADD COLUMN discovered_recipes TEXT NOT NULL DEFAULT '[]';
ALTER TABLE players ADD COLUMN crafting_specialization VARCHAR(64);
