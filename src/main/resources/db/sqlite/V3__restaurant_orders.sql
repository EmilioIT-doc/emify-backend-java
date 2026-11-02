-- V3 (SQLite) — equivalente a db/migration/V3__restaurant_orders.sql

CREATE TABLE restaurant_orders (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id     BIGINT          NOT NULL REFERENCES restaurant_locations(id) ON DELETE CASCADE,
    mode            VARCHAR(20)     NOT NULL,
    customer_name   VARCHAR(255)    NOT NULL,
    customer_phone  VARCHAR(255)    NOT NULL,
    address         VARCHAR(500)    NULL,
    payment_method  VARCHAR(30)     NOT NULL,
    allergies       TEXT            NULL,
    notes           TEXT            NULL,
    subtotal        DECIMAL(10, 2)  NOT NULL,
    status          VARCHAR(30)     NOT NULL DEFAULT 'received',
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE restaurant_order_items (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id        BIGINT          NOT NULL REFERENCES restaurant_orders(id) ON DELETE CASCADE,
    menu_item_id    BIGINT          NULL REFERENCES menu_items(id) ON DELETE SET NULL,
    item_name       VARCHAR(255)    NOT NULL,
    unit_price      DECIMAL(8, 2)   NOT NULL,
    quantity        INTEGER         NOT NULL
);

CREATE INDEX idx_restaurant_orders_location_id ON restaurant_orders(location_id);
CREATE INDEX idx_restaurant_orders_created_at ON restaurant_orders(created_at);
CREATE INDEX idx_restaurant_order_items_order_id ON restaurant_order_items(order_id);
CREATE INDEX idx_restaurant_order_items_menu_item_id ON restaurant_order_items(menu_item_id);
