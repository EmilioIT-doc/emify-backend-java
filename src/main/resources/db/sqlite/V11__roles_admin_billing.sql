-- V11 (SQLite) — equivalente a db/migration/V11__roles_admin_billing.sql
-- Super admin, recepcionista, suspensión de negocios, planes, pagos y bitácora de auditoría (solo INSERT).

ALTER TABLE users ADD COLUMN platform_role     VARCHAR(20)  NULL;
ALTER TABLE users ADD COLUMN suspended_at      TIMESTAMP    NULL;
ALTER TABLE users ADD COLUMN suspension_reason VARCHAR(255) NULL;

ALTER TABLE barbershop_staff ADD COLUMN is_receptionist BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE barbershop_staff ADD COLUMN bookable        BOOLEAN NOT NULL DEFAULT 1;

CREATE TABLE plans (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    code             VARCHAR(40)   NOT NULL UNIQUE,
    name             VARCHAR(100)  NOT NULL,
    description      TEXT          NULL,
    price            NUMERIC(10,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    billing_period   VARCHAR(10)   NOT NULL CHECK (billing_period IN ('monthly', 'yearly')),
    max_locations    INTEGER       NULL,
    is_active        BOOLEAN       NOT NULL DEFAULT 1,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL
);

ALTER TABLE barbershops ADD COLUMN plan_id           INTEGER      NULL REFERENCES plans(id);
ALTER TABLE barbershops ADD COLUMN suspended_at      TIMESTAMP    NULL;
ALTER TABLE barbershops ADD COLUMN suspension_reason VARCHAR(255) NULL;
ALTER TABLE barbershops ADD COLUMN suspended_by      INTEGER      NULL REFERENCES users(id);

ALTER TABLE restaurants ADD COLUMN plan_id           INTEGER      NULL REFERENCES plans(id);
ALTER TABLE restaurants ADD COLUMN suspended_at      TIMESTAMP    NULL;
ALTER TABLE restaurants ADD COLUMN suspension_reason VARCHAR(255) NULL;
ALTER TABLE restaurants ADD COLUMN suspended_by      INTEGER      NULL REFERENCES users(id);

CREATE TABLE subscription_payments (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    business_type   VARCHAR(20)   NOT NULL CHECK (business_type IN ('barbershop', 'restaurant')),
    business_id     INTEGER       NOT NULL,
    plan_id         INTEGER       NULL REFERENCES plans(id),
    amount          NUMERIC(10,2) NOT NULL,
    currency        VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    method          VARCHAR(20)   NOT NULL CHECK (method IN ('clip', 'transferencia', 'efectivo', 'tarjeta', 'otro')),
    reference       VARCHAR(120)  NULL,
    paid_at         TIMESTAMP     NOT NULL,
    period_start    DATE          NULL,
    period_end      DATE          NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'paid' CHECK (status IN ('paid', 'void')),
    notes           VARCHAR(255)  NULL,
    recorded_by     INTEGER       NULL REFERENCES users(id),
    voided_at       TIMESTAMP     NULL,
    voided_by       INTEGER       NULL REFERENCES users(id),
    void_reason     VARCHAR(255)  NULL,
    created_at      TIMESTAMP     NOT NULL
);

CREATE INDEX idx_subscription_payments_business ON subscription_payments(business_type, business_id);
CREATE INDEX idx_subscription_payments_paid_at  ON subscription_payments(paid_at);

CREATE TABLE admin_audit_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_id      INTEGER       NULL,
    admin_email   VARCHAR(255)  NULL,
    action        VARCHAR(60)   NOT NULL,
    target_type   VARCHAR(30)   NULL,
    target_id     VARCHAR(60)   NULL,
    reason        VARCHAR(500)  NULL,
    before_data   TEXT          NULL,
    after_data    TEXT          NULL,
    ip_address    VARCHAR(64)   NULL,
    user_agent    VARCHAR(512)  NULL,
    session_id    INTEGER       NULL,
    created_at    TIMESTAMP     NOT NULL
);

CREATE INDEX idx_admin_audit_created ON admin_audit_log(created_at);
CREATE INDEX idx_admin_audit_target  ON admin_audit_log(target_type, target_id);

CREATE TRIGGER admin_audit_log_no_update BEFORE UPDATE ON admin_audit_log
BEGIN
    SELECT RAISE(ABORT, 'admin_audit_log solo admite INSERT (bitácora de auditoría)');
END;

CREATE TRIGGER admin_audit_log_no_delete BEFORE DELETE ON admin_audit_log
BEGIN
    SELECT RAISE(ABORT, 'admin_audit_log solo admite INSERT (bitácora de auditoría)');
END;

ALTER TABLE user_sessions ADD COLUMN elevated_until TIMESTAMP NULL;
