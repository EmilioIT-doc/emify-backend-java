-- =============================================================
-- V11: super admin de la plataforma, recepcionista por sucursal, suspensión de negocios,
--      planes, pagos de suscripción y bitácora de auditoría.
--
-- users.platform_role: 'SUPER_ADMIN' o NULL. No se asigna desde la app: lo sincroniza
--   SuperAdminBootstrap con la lista emify.admin.super-admins (variable EMIFY_SUPER_ADMINS).
-- users.suspended_at / suspension_reason: cuenta suspendida por el super admin (además is_active = FALSE, que es lo
--   que cierra sus sesiones y le impide entrar). El detalle completo queda en admin_audit_log.
-- barbershop_staff.is_receptionist: lo elige el dueño (uno por sucursal). Maneja calendario general,
--   productos, catálogo, clientes e inicio de SU sucursal.
-- barbershop_staff.bookable: si aparece en la página de reservas y tiene agenda propia
--   (una recepcionista que no corta pelo = FALSE).
-- barbershops / restaurants: plan_id + suspensión (suspended_at, motivo, quién). Suspendido = reservas
--   pausadas y panel en solo lectura. La suscripción sigue en subscription_status/starts_at/ends_at.
-- subscription_payments: pagos registrados por el super admin (Clip después). No se editan ni borran:
--   un error se corrige anulando (status = 'void') con motivo.
-- admin_audit_log: cada consulta y acción del super admin. Solo se agrega: un trigger impide
--   UPDATE y DELETE.
-- user_sessions.elevated_until: hasta cuándo vale la reautenticación (contraseña) del super admin
--   para acciones sensibles.
-- =============================================================

ALTER TABLE users ADD COLUMN platform_role     VARCHAR(20)  NULL;
ALTER TABLE users ADD COLUMN suspended_at      TIMESTAMP    NULL;
ALTER TABLE users ADD COLUMN suspension_reason VARCHAR(255) NULL;

ALTER TABLE barbershop_staff ADD COLUMN is_receptionist BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE barbershop_staff ADD COLUMN bookable        BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE plans (
    id               BIGSERIAL     PRIMARY KEY,
    code             VARCHAR(40)   NOT NULL UNIQUE,
    name             VARCHAR(100)  NOT NULL,
    description      TEXT          NULL,
    price            NUMERIC(10,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    billing_period   VARCHAR(10)   NOT NULL CHECK (billing_period IN ('monthly', 'yearly')),
    max_locations    INTEGER       NULL,
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL
);

ALTER TABLE barbershops ADD COLUMN plan_id           BIGINT       NULL REFERENCES plans(id);
ALTER TABLE barbershops ADD COLUMN suspended_at      TIMESTAMP    NULL;
ALTER TABLE barbershops ADD COLUMN suspension_reason VARCHAR(255) NULL;
ALTER TABLE barbershops ADD COLUMN suspended_by      BIGINT       NULL REFERENCES users(id);

ALTER TABLE restaurants ADD COLUMN plan_id           BIGINT       NULL REFERENCES plans(id);
ALTER TABLE restaurants ADD COLUMN suspended_at      TIMESTAMP    NULL;
ALTER TABLE restaurants ADD COLUMN suspension_reason VARCHAR(255) NULL;
ALTER TABLE restaurants ADD COLUMN suspended_by      BIGINT       NULL REFERENCES users(id);

CREATE TABLE subscription_payments (
    id              BIGSERIAL     PRIMARY KEY,
    business_type   VARCHAR(20)   NOT NULL CHECK (business_type IN ('barbershop', 'restaurant')),
    business_id     BIGINT        NOT NULL,
    plan_id         BIGINT        NULL REFERENCES plans(id),
    amount          NUMERIC(10,2) NOT NULL,
    currency        VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    method          VARCHAR(20)   NOT NULL CHECK (method IN ('clip', 'transferencia', 'efectivo', 'tarjeta', 'otro')),
    reference       VARCHAR(120)  NULL,
    paid_at         TIMESTAMP     NOT NULL,
    period_start    DATE          NULL,
    period_end      DATE          NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'paid' CHECK (status IN ('paid', 'void')),
    notes           VARCHAR(255)  NULL,
    recorded_by     BIGINT        NULL REFERENCES users(id),
    voided_at       TIMESTAMP     NULL,
    voided_by       BIGINT        NULL REFERENCES users(id),
    void_reason     VARCHAR(255)  NULL,
    created_at      TIMESTAMP     NOT NULL
);

CREATE INDEX idx_subscription_payments_business ON subscription_payments(business_type, business_id);
CREATE INDEX idx_subscription_payments_paid_at  ON subscription_payments(paid_at);

CREATE TABLE admin_audit_log (
    id            BIGSERIAL     PRIMARY KEY,
    admin_id      BIGINT        NULL,
    admin_email   VARCHAR(255)  NULL,
    action        VARCHAR(60)   NOT NULL,
    target_type   VARCHAR(30)   NULL,
    target_id     VARCHAR(60)   NULL,
    reason        VARCHAR(500)  NULL,
    before_data   TEXT          NULL,
    after_data    TEXT          NULL,
    ip_address    VARCHAR(64)   NULL,
    user_agent    VARCHAR(512)  NULL,
    session_id    BIGINT        NULL,
    created_at    TIMESTAMP     NOT NULL
);

CREATE INDEX idx_admin_audit_created ON admin_audit_log(created_at);
CREATE INDEX idx_admin_audit_target  ON admin_audit_log(target_type, target_id);

CREATE OR REPLACE FUNCTION admin_audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_log solo admite INSERT (bitácora de auditoría)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER admin_audit_log_no_change
    BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION admin_audit_log_append_only();

ALTER TABLE user_sessions ADD COLUMN elevated_until TIMESTAMP NULL;
