-- =============================================================
-- V6: método de pago de las citas atendidas (solo informativo, Emify no cobra nada).
-- Lo captura el barbero en el pop-up de asistencia al marcar "Sí llegó".
-- NULL = cita no atendida, o atendida antes de que existiera este dato.
-- =============================================================
ALTER TABLE appointments
    ADD COLUMN payment_method VARCHAR(20) NULL
        CHECK (payment_method IN ('efectivo', 'tarjeta', 'transferencia'));
