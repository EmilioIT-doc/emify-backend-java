-- =============================================================
-- V7: horario con hora de comida y tiempo entre citas por sucursal.
-- - availability.break_start / break_end: comida del día (NULL = sin comida).
--   En ese rango no se generan horarios ni se aceptan citas.
-- - barbershop_locations.buffer_minutes: descanso entre una cita y la siguiente.
--   Default 10 = el colchón fijo que ya se usaba al calcular horarios.
-- =============================================================
ALTER TABLE availability
    ADD COLUMN break_start TIME NULL,
    ADD COLUMN break_end   TIME NULL;

ALTER TABLE barbershop_locations
    ADD COLUMN buffer_minutes INTEGER NOT NULL DEFAULT 10;
