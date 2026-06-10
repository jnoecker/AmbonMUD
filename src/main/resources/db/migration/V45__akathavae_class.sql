-- Akathavae as a real class: pledging switches playerClass to AKATHAVAE and
-- stashes the previous class here so renouncing can restore it.
ALTER TABLE players ADD COLUMN pre_akathavae_class VARCHAR(32);
