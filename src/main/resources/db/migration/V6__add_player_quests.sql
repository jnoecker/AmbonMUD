ALTER TABLE players
    ADD COLUMN active_quests TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN completed_quest_ids TEXT NOT NULL DEFAULT '[]';
