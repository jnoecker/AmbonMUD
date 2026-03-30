CREATE TABLE houses (
    owner_id          BIGINT       PRIMARY KEY,
    owner_name        VARCHAR(16)  NOT NULL,
    owner_name_lower  VARCHAR(16)  NOT NULL,
    rooms             TEXT         NOT NULL DEFAULT '[]',
    created_at_epoch_ms BIGINT     NOT NULL
);

CREATE UNIQUE INDEX idx_houses_owner_name_lower ON houses (owner_name_lower);
