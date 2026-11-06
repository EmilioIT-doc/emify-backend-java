-- =============================================================
-- V8: reserva con varios servicios, productos, notas y fotos.
-- - appointments.group_token: una visita con varios servicios (Corte + Barba + Cejas) se guarda
--   como citas encadenadas, una por servicio, con el mismo group_token. Así ventas, top servicios
--   y top personal siguen contando cada servicio. NULL = cita de un solo servicio.
-- - service_images: hasta 3 fotos del trabajo por servicio (las sube el dueño; las ve el cliente).
-- - appointment_images: fotos de referencia que sube el cliente (máx. 3 por visita), ligadas a la
--   cita del servicio al que corresponden.
-- - appointment_products: productos apartados en la visita (copia del nombre y precio del momento).
--   Van en la primera cita del grupo. Por ahora NO cuentan como venta.
-- =============================================================
ALTER TABLE appointments
    ADD COLUMN group_token VARCHAR(32) NULL;

CREATE INDEX idx_appointments_group_token ON appointments(group_token);

CREATE TABLE service_images (
    id          BIGSERIAL PRIMARY KEY,
    service_id  BIGINT          NOT NULL,
    image_url   VARCHAR(500)    NOT NULL,
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_service_images_service
        FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);

CREATE INDEX idx_service_images_service_id ON service_images(service_id);

CREATE TABLE appointment_images (
    id              BIGSERIAL PRIMARY KEY,
    appointment_id  BIGINT          NOT NULL,
    image_url       VARCHAR(500)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_appointment_images_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE CASCADE
);

CREATE INDEX idx_appointment_images_appointment_id ON appointment_images(appointment_id);

CREATE TABLE appointment_products (
    id              BIGSERIAL PRIMARY KEY,
    appointment_id  BIGINT          NOT NULL,
    product_id      BIGINT          NULL,
    product_name    VARCHAR(255)    NOT NULL,
    unit_price      DECIMAL(10, 2)  NOT NULL,
    quantity        INTEGER         NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_appointment_products_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE CASCADE,
    CONSTRAINT fk_appointment_products_product
        FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL
);

CREATE INDEX idx_appointment_products_appointment_id ON appointment_products(appointment_id);
