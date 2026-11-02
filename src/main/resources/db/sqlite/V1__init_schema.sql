-- =============================================================
-- V1__init_schema.sql  (versión SQLite — solo para pruebas locales)
--
-- Traducción de db/migration/V1__init_schema.sql (PostgreSQL):
--   * BIGSERIAL            → INTEGER PRIMARY KEY AUTOINCREMENT
--   * ENUM de PostgreSQL   → VARCHAR + CHECK con los mismos valores
--   * NOW()                → CURRENT_TIMESTAMP
-- Si cambias el esquema de PostgreSQL, refleja el cambio aquí con el mismo número de versión.
-- =============================================================

CREATE TABLE users (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    name                VARCHAR(255)    NOT NULL,
    email               VARCHAR(255)    NOT NULL UNIQUE,
    avatar_url          VARCHAR(255)    NULL,
    email_verified_at   TIMESTAMP       NULL,
    password            VARCHAR(255)    NOT NULL,
    phone               VARCHAR(255)    NOT NULL,
    phone_code          VARCHAR(5)      NOT NULL DEFAULT '+52',
    role                VARCHAR(20)     NOT NULL CHECK (role IN ('client', 'barber', 'owner')),
    is_active           BOOLEAN         NOT NULL DEFAULT 1,
    remember_token      VARCHAR(100)    NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE email_verification_codes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    email       VARCHAR(255)    NOT NULL,
    code        VARCHAR(6)      NOT NULL,
    expires_at  TIMESTAMP       NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE barbershops (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    name                    VARCHAR(255)    NOT NULL,
    description             TEXT            NULL,
    website                 VARCHAR(255)    NULL,
    owner_id                BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_active               BOOLEAN         NOT NULL DEFAULT 1,
    subscription_status     VARCHAR(50)     NOT NULL DEFAULT 'trial',
    subscription_starts_at  TIMESTAMP       NULL,
    subscription_ends_at    TIMESTAMP       NULL,
    clip_customer_id        VARCHAR(255)    NULL,
    last_payment_id         VARCHAR(255)    NULL,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE barbershop_locations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    barbershop_id   BIGINT          NOT NULL REFERENCES barbershops(id) ON DELETE CASCADE,
    name            VARCHAR(255)    NOT NULL,
    address         VARCHAR(255)    NOT NULL,
    city            VARCHAR(255)    NOT NULL,
    state           VARCHAR(255)    NOT NULL,
    latitude        DECIMAL(10, 7)  NULL,
    longitude       DECIMAL(10, 7)  NULL,
    phone           VARCHAR(255)    NULL,
    phone_code      VARCHAR(5)      NOT NULL DEFAULT '+52',
    is_active       BOOLEAN         NOT NULL DEFAULT 1,
    is_default      BOOLEAN         NOT NULL DEFAULT 0,
    invite_code     VARCHAR(255)    NULL UNIQUE,
    service_type    VARCHAR(20)     NOT NULL DEFAULT 'physical' CHECK (service_type IN ('physical', 'home', 'online')),
    serves_physical BOOLEAN         NOT NULL DEFAULT 0,
    serves_home     BOOLEAN         NOT NULL DEFAULT 0,
    serves_online   BOOLEAN         NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMP       NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE barbershop_images (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    barbershop_id   BIGINT          NOT NULL REFERENCES barbershops(id) ON DELETE CASCADE,
    url             VARCHAR(255)    NOT NULL,
    type            VARCHAR(20)     NOT NULL CHECK (type IN ('logo', 'banner', 'gallery')),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE services (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id BIGINT          NOT NULL REFERENCES barbershop_locations(id) ON DELETE CASCADE,
    name        VARCHAR(255)    NOT NULL,
    description TEXT            NULL,
    price       DECIMAL(8, 2)   NOT NULL,
    duration    INTEGER         NOT NULL, -- en minutos
    is_active   BOOLEAN         NOT NULL DEFAULT 1,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE products (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id BIGINT          NOT NULL REFERENCES barbershop_locations(id) ON DELETE CASCADE,
    name        VARCHAR(255)    NOT NULL,
    description TEXT            NULL,
    price       DECIMAL(10, 2)  NOT NULL,
    image_url   VARCHAR(255)    NULL,
    is_active   BOOLEAN         NOT NULL DEFAULT 1,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE barbershop_staff (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    location_id BIGINT      NOT NULL REFERENCES barbershop_locations(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL CHECK (role IN ('barber', 'admin', 'manager')),
    is_active   BOOLEAN     NOT NULL DEFAULT 1,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE staff_services (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    staff_id    BIGINT      NOT NULL REFERENCES barbershop_staff(id) ON DELETE CASCADE,
    service_id  BIGINT      NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    is_active   BOOLEAN     NOT NULL DEFAULT 1,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE availability (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    staff_id    BIGINT      NULL REFERENCES barbershop_staff(id) ON DELETE CASCADE,
    location_id BIGINT      NULL REFERENCES barbershop_locations(id) ON DELETE CASCADE,
    day_of_week SMALLINT    NOT NULL,   -- 0=Domingo, 6=Sábado
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    is_active   BOOLEAN     NOT NULL DEFAULT 1,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE appointments (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    staff_id            BIGINT          NULL REFERENCES barbershop_staff(id) ON DELETE CASCADE,
    service_id          BIGINT          NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    location_id         BIGINT          NOT NULL REFERENCES barbershop_locations(id) ON DELETE CASCADE,
    start_time          TIMESTAMP       NOT NULL,
    end_time            TIMESTAMP       NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'cancelled', 'completed')),
    notes               TEXT            NULL,
    reference_image_url VARCHAR(255)    NULL,
    token               VARCHAR(255)    NULL UNIQUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_barbershops_owner_id         ON barbershops(owner_id);
CREATE INDEX idx_locations_barbershop_id      ON barbershop_locations(barbershop_id);
CREATE INDEX idx_services_location_id         ON services(location_id);
CREATE INDEX idx_products_location_id         ON products(location_id);
CREATE INDEX idx_staff_user_id                ON barbershop_staff(user_id);
CREATE INDEX idx_staff_location_id            ON barbershop_staff(location_id);
CREATE INDEX idx_staff_services_staff_id      ON staff_services(staff_id);
CREATE INDEX idx_staff_services_service_id    ON staff_services(service_id);
CREATE INDEX idx_availability_staff_id        ON availability(staff_id);
CREATE INDEX idx_availability_location_id     ON availability(location_id);
CREATE INDEX idx_appointments_user_id         ON appointments(user_id);
CREATE INDEX idx_appointments_staff_id        ON appointments(staff_id);
CREATE INDEX idx_appointments_location_id     ON appointments(location_id);
CREATE INDEX idx_appointments_status          ON appointments(status);
CREATE INDEX idx_appointments_start_time      ON appointments(start_time);
CREATE INDEX idx_email_verification_email     ON email_verification_codes(email);
