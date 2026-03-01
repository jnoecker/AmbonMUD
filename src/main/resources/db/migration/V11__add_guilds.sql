CREATE TABLE guilds (
    id                  VARCHAR(64)  NOT NULL,
    name                VARCHAR(64)  NOT NULL,
    name_lower          VARCHAR(64)  NOT NULL,
    tag                 VARCHAR(5)   NOT NULL,
    tag_lower           VARCHAR(5)   NOT NULL,
    leader_id           BIGINT       NOT NULL,
    motd                TEXT         NULL,
    members             TEXT         NOT NULL DEFAULT '{}',
    created_at_epoch_ms BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_guilds_name_lower ON guilds (name_lower);
CREATE UNIQUE INDEX idx_guilds_tag_lower ON guilds (tag_lower);

ALTER TABLE players
    ADD COLUMN guild_id VARCHAR(64) NULL DEFAULT NULL;
