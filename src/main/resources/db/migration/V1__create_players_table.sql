CREATE SEQUENCE player_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE players (
    id         BIGINT       PRIMARY KEY DEFAULT nextval('player_id_seq'),
    name       VARCHAR(16)  NOT NULL,
    name_lower VARCHAR(16)  NOT NULL,
    room_id    VARCHAR(128) NOT NULL,
    constitution INT        NOT NULL DEFAULT 0,
    level      INT          NOT NULL DEFAULT 1,
    xp_total   BIGINT       NOT NULL DEFAULT 0,
    created_at_epoch_ms BIGINT NOT NULL,
    last_seen_epoch_ms  BIGINT NOT NULL,
    password_hash VARCHAR(72) NOT NULL DEFAULT '',
    ansi_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    is_staff   BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX idx_players_name_lower ON players (name_lower);
