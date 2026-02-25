ALTER TABLE players
    ADD COLUMN unlocked_achievement_ids TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN achievement_progress      TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN active_title              VARCHAR(64) NULL DEFAULT NULL;
