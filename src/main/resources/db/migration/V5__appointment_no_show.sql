-- =============================================================
-- V5: estado "no_show" para citas a las que el cliente no llegó.
-- "completed" = sí llegó (cuenta como venta); "no_show" = no llegó (no cuenta).
-- (ALTER TYPE ... ADD VALUE dentro de transacción requiere PostgreSQL 12+)
-- =============================================================
ALTER TYPE appointment_status ADD VALUE IF NOT EXISTS 'no_show';
