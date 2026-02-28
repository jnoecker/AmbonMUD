CREATE TABLE world_content (
    source_name TEXT NOT NULL PRIMARY KEY,
    zone TEXT NOT NULL,
    content TEXT NOT NULL,
    load_order INTEGER NOT NULL,
    imported_at_epoch_ms BIGINT NOT NULL
);

CREATE INDEX idx_world_content_load_order ON world_content (load_order);
CREATE INDEX idx_world_content_zone ON world_content (zone);
