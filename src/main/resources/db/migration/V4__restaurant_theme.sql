-- Color de acento configurable por el dueño del restaurante (dashboard + página pública de pedidos).
ALTER TABLE restaurants
    ADD COLUMN theme_color VARCHAR(7) NOT NULL DEFAULT '#ea580c';
