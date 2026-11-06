-- V8 (SQLite) — equivalente a db/migration/V8__booking_multi_service.sql
-- Reserva con varios servicios (citas encadenadas por group_token), fotos por servicio (máx. 3),
-- fotos de referencia del cliente y productos apartados en la visita.
-- ⚠️ Desde aquí appointment_images y appointment_products tienen FK hacia appointments: si algún día
-- se reconstruye la tabla appointments (como en V5), hay que considerarlas.
ALTER TABLE appointments ADD COLUMN group_token VARCHAR(32) NULL;

CREATE INDEX idx_appointments_group_token ON appointments(group_token);

CREATE TABLE service_images (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id  BIGINT          NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    image_url   VARCHAR(500)    NOT NULL,
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_images_service_id ON service_images(service_id);

CREATE TABLE appointment_images (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    appointment_id  BIGINT          NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    image_url       VARCHAR(500)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_appointment_images_appointment_id ON appointment_images(appointment_id);

CREATE TABLE appointment_products (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    appointment_id  BIGINT          NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    product_id      BIGINT          NULL REFERENCES products(id) ON DELETE SET NULL,
    product_name    VARCHAR(255)    NOT NULL,
    unit_price      DECIMAL(10, 2)  NOT NULL,
    quantity        INTEGER         NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_appointment_products_appointment_id ON appointment_products(appointment_id);
