-- =============================================================
-- MÓDULO RESTAURANTE
-- Mismo patrón que barbershops / barbershop_locations / services,
-- pero para el rubro restaurante: menú por categorías + modalidades
-- de atención (mesa / domicilio / recoger en tienda).
-- =============================================================

-- =============================================================
-- TABLA: restaurants
-- =============================================================

CREATE TABLE restaurants (
    id                      BIGSERIAL       PRIMARY KEY,
    name                    VARCHAR(255)    NOT NULL,
    description             TEXT            NULL,
    website                 VARCHAR(255)    NULL,
    cuisine_type            VARCHAR(100)    NULL,
    order_mode_table        BOOLEAN         NOT NULL DEFAULT FALSE,
    order_mode_delivery     BOOLEAN         NOT NULL DEFAULT FALSE,
    order_mode_pickup       BOOLEAN         NOT NULL DEFAULT FALSE,
    owner_id                BIGINT          NOT NULL,
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,
    subscription_status     VARCHAR(50)     NOT NULL DEFAULT 'trial', -- trial | active | expired
    subscription_starts_at  TIMESTAMP       NULL,
    subscription_ends_at    TIMESTAMP       NULL,
    created_at              TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_restaurants_owner
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: restaurant_locations
-- =============================================================

CREATE TABLE restaurant_locations (
    id              BIGSERIAL       PRIMARY KEY,
    restaurant_id   BIGINT          NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    address         VARCHAR(255)    NOT NULL,
    city            VARCHAR(255)    NOT NULL,
    state           VARCHAR(255)    NOT NULL,
    latitude        DECIMAL(10, 7)  NULL,
    longitude       DECIMAL(10, 7)  NULL,
    phone           VARCHAR(255)    NULL,   -- también es el número de WhatsApp al que llegan los pedidos
    phone_code      VARCHAR(5)      NOT NULL DEFAULT '+52',
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP       NULL,   -- soft delete
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_restaurant_locations_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: menu_categories
-- =============================================================

CREATE TABLE menu_categories (
    id              BIGSERIAL       PRIMARY KEY,
    location_id     BIGINT          NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_menu_categories_location
        FOREIGN KEY (location_id) REFERENCES restaurant_locations(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: menu_items
-- =============================================================

CREATE TABLE menu_items (
    id              BIGSERIAL       PRIMARY KEY,
    category_id     BIGINT          NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT            NULL,
    price           DECIMAL(8, 2)   NOT NULL,
    image_url       VARCHAR(500)    NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_menu_items_category
        FOREIGN KEY (category_id) REFERENCES menu_categories(id) ON DELETE CASCADE
);

-- =============================================================
-- TABLA: restaurant_availability
-- Igual que "availability" pero para restaurant_locations — no se
-- reutiliza la tabla original porque su FK apunta específicamente
-- a barbershop_locations.
-- =============================================================

CREATE TABLE restaurant_availability (
    id              BIGSERIAL       PRIMARY KEY,
    location_id     BIGINT          NOT NULL,
    day_of_week     SMALLINT        NOT NULL,  -- 0=Domingo, 6=Sábado
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_restaurant_availability_location
        FOREIGN KEY (location_id) REFERENCES restaurant_locations(id) ON DELETE CASCADE
);

CREATE INDEX idx_restaurant_locations_restaurant_id ON restaurant_locations(restaurant_id);
CREATE INDEX idx_menu_categories_location_id ON menu_categories(location_id);
CREATE INDEX idx_menu_items_category_id ON menu_items(category_id);
CREATE INDEX idx_restaurant_availability_location_id ON restaurant_availability(location_id);
