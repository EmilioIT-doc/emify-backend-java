-- =============================================================
-- V10: estilo de la página pública de reservas, elegido por el dueño en Ajustes.
-- - booking_theme: clasico | noche | tradicional | urbano (se valida en BookingTheme.java).
-- - booking_accent: color principal dentro del estilo (uno de sus 4 colores). NULL = el del estilo.
-- =============================================================
ALTER TABLE barbershops
    ADD COLUMN booking_theme  VARCHAR(20) NOT NULL DEFAULT 'clasico',
    ADD COLUMN booking_accent VARCHAR(7)  NULL;
