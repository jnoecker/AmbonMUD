CREATE TABLE world_state (
    id TEXT NOT NULL PRIMARY KEY DEFAULT 'singleton',
    door_states TEXT NOT NULL DEFAULT '{}',
    container_states TEXT NOT NULL DEFAULT '{}',
    lever_states TEXT NOT NULL DEFAULT '{}',
    container_items TEXT NOT NULL DEFAULT '{}'
);
INSERT INTO world_state (id) VALUES ('singleton') ON CONFLICT DO NOTHING;
