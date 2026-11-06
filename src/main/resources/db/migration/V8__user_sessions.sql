-- =============================================================
-- V8: sesiones del lado del servidor (reemplazan al JWT) y registro de actividad.
--
-- user_sessions: una fila por dispositivo con sesión. La cookie HttpOnly lleva un token aleatorio;
--   aquí solo se guarda su SHA-256 (token_hash). Revocar = llenar revoked_at: la siguiente petición
--   con esa cookie ya no entra. Fechas en UTC.
-- auth_events: inicios de sesión, fallidos, cierres, revocaciones, cambios de contraseña.
-- email_verification_codes.attempts: intentos fallidos con un código (a los 5 se invalida).
-- =============================================================

CREATE TABLE user_sessions (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash           VARCHAR(64)  NOT NULL UNIQUE,
    previous_token_hash  VARCHAR(64)  NULL,
    created_at           TIMESTAMP    NOT NULL,
    last_seen_at         TIMESTAMP    NOT NULL,
    rotated_at           TIMESTAMP    NOT NULL,
    expires_at           TIMESTAMP    NOT NULL,
    revoked_at           TIMESTAMP    NULL,
    revoked_reason       VARCHAR(40)  NULL,
    ip_address           VARCHAR(64)  NULL,
    user_agent           VARCHAR(512) NULL
);

CREATE INDEX idx_user_sessions_user     ON user_sessions(user_id, revoked_at);
CREATE INDEX idx_user_sessions_previous ON user_sessions(previous_token_hash);

CREATE TABLE auth_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id  BIGINT       NULL,
    event_type  VARCHAR(40)  NOT NULL,
    ip_address  VARCHAR(64)  NULL,
    user_agent  VARCHAR(512) NULL,
    detail      VARCHAR(255) NULL,
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_auth_events_user ON auth_events(user_id, created_at);

ALTER TABLE email_verification_codes
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
