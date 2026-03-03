ALTER TABLE players ADD COLUMN inventory_items TEXT NOT NULL DEFAULT '[]';
ALTER TABLE players ADD COLUMN equipped_items TEXT NOT NULL DEFAULT '{}';
