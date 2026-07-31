-- =============================================================
-- V1__init_schema.sql
-- Schema inicial completo para emify (sistema de citas de barbería)
-- Consolidado desde migraciones Laravel → Flyway/PostgreSQL
-- =============================================================

-- =============================================================
-- TIPOS ENUM
-- =============================================================

CREATE TYPE user_role AS ENUM ('client', 'barber', 'owner');
CREATE TYPE staff_role AS ENUM ('barber', 'admin', 'manager');
CREATE TYPE appointment_status AS ENUM ('pending', 'confirmed', 'cancelled', 'completed');
CREATE TYPE barbershop_image_type AS ENUM ('logo', 'banner', 'gallery');
CREATE TYPE service_type_enum AS ENUM ('physical', 'home', 'online');

-- =============================================================
-- TABLA: users
-- =============================================================

CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255)        NOT NULL,
    email               VARCHAR(255)        NOT NULL UNIQUE,
    avatar_url          VARCHAR(255)        NULL,
    email_verified_at   TIMESTAMP           NULL,
    password            VARCHAR(255)        NOT NULL,
    phone               VARCHAR(255)        NOT NULL,
    phone_code          VARCHAR(5)          NOT NULL DEFAULT '+52',
    role                user_role           NOT NULL,
    is_active           BOOLEAN             NOT NULL DEFAULT TRUE,
    remember_token      VARCHAR(100)        NULL,
    created_at          TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP           NOT NULL DEFAULT NOW()
);

-- =============================================================
-- TABLA: email_verification_codes
-- =============================================================

CREATE TABLE email_verification_codes (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255)    NOT NULL,
    code        VARCHAR(6)      NOT NULL,
    expires_at  TIMESTAMP       NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- =============================================================
-- TABLA: barbershops
-- =============================================================

CREATE TABLE barbershops (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(255)    NOT NULL,
    description             TEXT            NULL,
    website                 VARCHAR(255)    NULL,
    owner_id                BIGINT          NOT NULL,
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,
    subscription_status     VARCHAR(50)     NOT NULL DEFAULT 'trial', -- trial | active | expired
    subscription_starts_at  TIMESTAMP       NULL,
    subscription_ends_at    TIMESTAMP       NULL,
    clip_customer_id        VARCHAR(255)    NULL,
    last_payment_id         VARCHAR(255)    NULL,
    created_at              TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_barbershops_owner
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: barbershop_locations
-- =============================================================

CREATE TABLE barbershop_locations (
    id              BIGSERIAL PRIMARY KEY,
    barbershop_id   BIGINT          NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    address         VARCHAR(255)    NOT NULL,
    city            VARCHAR(255)    NOT NULL,
    state           VARCHAR(255)    NOT NULL,
    latitude        DECIMAL(10, 7)  NULL,
    longitude       DECIMAL(10, 7)  NULL,
    phone           VARCHAR(255)    NULL,
    phone_code      VARCHAR(5)      NOT NULL DEFAULT '+52',
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE,
    invite_code     VARCHAR(255)    NULL UNIQUE,
    service_type    service_type_enum NOT NULL DEFAULT 'physical',
    serves_physical BOOLEAN         NOT NULL DEFAULT FALSE,
    serves_home     BOOLEAN         NOT NULL DEFAULT FALSE,
    serves_online   BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP       NULL,  -- soft delete
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_locations_barbershop
        FOREIGN KEY (barbershop_id) REFERENCES barbershops(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: barbershop_images
-- =============================================================

CREATE TABLE barbershop_images (
    id              BIGSERIAL PRIMARY KEY,
    barbershop_id   BIGINT                  NOT NULL,
    url             VARCHAR(255)            NOT NULL,
    type            barbershop_image_type   NOT NULL,
    created_at      TIMESTAMP               NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP               NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_images_barbershop
        FOREIGN KEY (barbershop_id) REFERENCES barbershops(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: services
-- =============================================================

CREATE TABLE services (
    id          BIGSERIAL PRIMARY KEY,
    location_id BIGINT          NOT NULL,
    name        VARCHAR(255)    NOT NULL,
    description TEXT            NULL,
    price       DECIMAL(8, 2)   NOT NULL,
    duration    INTEGER         NOT NULL, -- en minutos
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_services_location
        FOREIGN KEY (location_id) REFERENCES barbershop_locations(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: products
-- =============================================================

CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    location_id BIGINT          NOT NULL,
    name        VARCHAR(255)    NOT NULL,
    description TEXT            NULL,
    price       DECIMAL(10, 2)  NOT NULL,
    image_url   VARCHAR(255)    NULL,
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_products_location
        FOREIGN KEY (location_id) REFERENCES barbershop_locations(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: barbershop_staff
-- =============================================================

CREATE TABLE barbershop_staff (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    location_id BIGINT      NOT NULL,
    role        staff_role  NOT NULL,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_staff_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_location
        FOREIGN KEY (location_id) REFERENCES barbershop_locations(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: staff_services
-- =============================================================

CREATE TABLE staff_services (
    id          BIGSERIAL PRIMARY KEY,
    staff_id    BIGINT      NOT NULL,
    service_id  BIGINT      NOT NULL,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_staff_services_staff
        FOREIGN KEY (staff_id) REFERENCES barbershop_staff(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_services_service
        FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: availability
-- =============================================================

CREATE TABLE availability (
    id          BIGSERIAL PRIMARY KEY,
    staff_id    BIGINT      NULL,       -- nullable (puede ser horario de sucursal)
    location_id BIGINT      NULL,       -- nullable (puede ser horario de staff)
    day_of_week SMALLINT    NOT NULL,   -- 0=Domingo, 6=Sábado
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_availability_staff
        FOREIGN KEY (staff_id) REFERENCES barbershop_staff(id) ON DELETE CASCADE,
    CONSTRAINT fk_availability_location
        FOREIGN KEY (location_id) REFERENCES barbershop_locations(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: appointments
-- =============================================================

CREATE TABLE appointments (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT              NOT NULL,
    staff_id            BIGINT              NULL,   -- nullable
    service_id          BIGINT              NOT NULL,
    location_id         BIGINT              NOT NULL,
    start_time          TIMESTAMP           NOT NULL,
    end_time            TIMESTAMP           NOT NULL,
    status              appointment_status  NOT NULL DEFAULT 'pending',
    notes               TEXT                NULL,
    reference_image_url VARCHAR(255)        NULL,
    token               VARCHAR(255)        NULL UNIQUE,
    created_at          TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP           NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_appointments_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_appointments_staff
        FOREIGN KEY (staff_id) REFERENCES barbershop_staff(id) ON DELETE CASCADE,
    CONSTRAINT fk_appointments_service
        FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE,
    CONSTRAINT fk_appointments_location
        FOREIGN KEY (location_id) REFERENCES barbershop_locations(id) ON DELETE CASCADE
);

-- =============================================================
-- ÍNDICES útiles para queries frecuentes
-- =============================================================

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