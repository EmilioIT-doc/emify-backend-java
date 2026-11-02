-- V4 (SQLite) — equivalente a db/migration/V4__restaurant_theme.sql
ALTER TABLE restaurants ADD COLUMN theme_color VARCHAR(7) NOT NULL DEFAULT '#ea580c';
