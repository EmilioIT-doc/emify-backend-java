-- V5 (SQLite) — equivalente a db/migration/V5__appointment_no_show.sql
-- SQLite no permite cambiar un CHECK, así que se reconstruye la tabla appointments
-- (ninguna otra tabla tiene llaves foráneas hacia ella).

CREATE TABLE appointments_new (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    staff_id            BIGINT          NULL REFERENCES barbershop_staff(id) ON DELETE CASCADE,
    service_id          BIGINT          NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    location_id         BIGINT          NOT NULL REFERENCES barbershop_locations(id) ON DELETE CASCADE,
    start_time          TIMESTAMP       NOT NULL,
    end_time            TIMESTAMP       NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'cancelled', 'completed', 'no_show')),
    notes               TEXT            NULL,
    reference_image_url VARCHAR(255)    NULL,
    token               VARCHAR(255)    NULL UNIQUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO appointments_new (id, user_id, staff_id, service_id, location_id, start_time, end_time, status,
                              notes, reference_image_url, token, created_at, updated_at)
SELECT id, user_id, staff_id, service_id, location_id, start_time, end_time, status,
       notes, reference_image_url, token, created_at, updated_at
FROM appointments;

DROP TABLE appointments;

ALTER TABLE appointments_new RENAME TO appointments;

CREATE INDEX idx_appointments_user_id     ON appointments(user_id);
CREATE INDEX idx_appointments_staff_id    ON appointments(staff_id);
CREATE INDEX idx_appointments_location_id ON appointments(location_id);
CREATE INDEX idx_appointments_status      ON appointments(status);
CREATE INDEX idx_appointments_start_time  ON appointments(start_time);
