-- V2 (SQLite) — equivalente a db/migration/V2__restaurant_module.sql

CREATE TABLE restaurants (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    name                    VARCHAR(255)    NOT NULL,
    description             TEXT            NULL,
    website                 VARCHAR(255)    NULL,
    cuisine_type            VARCHAR(100)    NULL,
    order_mode_table        BOOLEAN         NOT NULL DEFAULT 0,
    order_mode_delivery     BOOLEAN         NOT NULL DEFAULT 0,
    order_mode_pickup       BOOLEAN         NOT NULL DEFAULT 0,
    owner_id                BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_active               BOOLEAN         NOT NULL DEFAULT 1,
    subscription_status     VARCHAR(50)     NOT NULL DEFAULT 'trial',
    subscription_starts_at  TIMESTAMP       NULL,
    subscription_ends_at    TIMESTAMP       NULL,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE restaurant_locations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    restaurant_id   BIGINT          NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    name            VARCHAR(255)    NOT NULL,
    address         VARCHAR(255)    NOT NULL,
    city            VARCHAR(255)    NOT NULL,
    state           VARCHAR(255)    NOT NULL,
    latitude        DECIMAL(10, 7)  NULL,
    longitude       DECIMAL(10, 7)  NULL,
    phone           VARCHAR(255)    NULL,
    phone_code      VARCHAR(5)      NOT NULL DEFAULT '+52',
    is_active       BOOLEAN         NOT NULL DEFAULT 1,
    is_default      BOOLEAN         NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMP       NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE menu_categories (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id     BIGINT          NOT NULL REFERENCES restaurant_locations(id) ON DELETE CASCADE,
    name            VARCHAR(255)    NOT NULL,
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE menu_items (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id     BIGINT          NOT NULL REFERENCES menu_categories(id) ON DELETE CASCADE,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT            NULL,
    price           DECIMAL(8, 2)   NOT NULL,
    image_url       VARCHAR(500)    NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT 1,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE restaurant_availability (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id     BIGINT          NOT NULL REFERENCES restaurant_locations(id) ON DELETE CASCADE,
    day_of_week     SMALLINT        NOT NULL,  -- 0=Domingo, 6=Sábado
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT 1,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_restaurant_locations_restaurant_id ON restaurant_locations(restaurant_id);
CREATE INDEX idx_menu_categories_location_id ON menu_categories(location_id);
CREATE INDEX idx_menu_items_category_id ON menu_items(category_id);
CREATE INDEX idx_restaurant_availability_location_id ON restaurant_availability(location_id);
