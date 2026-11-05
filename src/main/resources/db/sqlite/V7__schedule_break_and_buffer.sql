-- V7 (SQLite) — equivalente a db/migration/V7__schedule_break_and_buffer.sql
-- Hora de comida por día y tiempo entre citas por sucursal (default 10 min).
ALTER TABLE availability ADD COLUMN break_start TIME NULL;
ALTER TABLE availability ADD COLUMN break_end TIME NULL;
ALTER TABLE barbershop_locations ADD COLUMN buffer_minutes INTEGER NOT NULL DEFAULT 10;
