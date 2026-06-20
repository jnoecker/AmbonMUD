-- Persisted cooldown for race-specific passive abilities. These abilities can cheat death, so the
-- cooldown must survive logout/login — otherwise a relog would reset it and let the same fight be
-- saved twice. Runtime ability cooldowns stay in-memory; only this one is persisted.
ALTER TABLE players ADD COLUMN racial_ability_cooldown_until_ms BIGINT NOT NULL DEFAULT 0;
