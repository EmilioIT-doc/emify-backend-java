-- V6 (SQLite) — equivalente a db/migration/V6__appointment_payment_method.sql
-- Método de pago de las citas atendidas (informativo). NULL = no aplica / sin registrar.
ALTER TABLE appointments
    ADD COLUMN payment_method VARCHAR(20) NULL
        CHECK (payment_method IN ('efectivo', 'tarjeta', 'transferencia'));
