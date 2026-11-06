-- V10 (SQLite) — equivalente a db/migration/V10__booking_theme.sql
-- Estilo de la página de reservas (clasico | noche | tradicional | urbano) y su color principal.
ALTER TABLE barbershops ADD COLUMN booking_theme VARCHAR(20) NOT NULL DEFAULT 'clasico';
ALTER TABLE barbershops ADD COLUMN booking_accent VARCHAR(7) NULL;
