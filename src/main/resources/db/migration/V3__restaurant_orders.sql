-- =============================================================
-- PEDIDOS DE RESTAURANTE
-- El pedido en sí se manda por WhatsApp directo del cliente al negocio
-- (no pasa por nuestro servidor) — esto solo guarda una copia para tener
-- el contacto del cliente y poder mostrar métricas en el dashboard
-- (ventas recientes, platillos más vendidos).
-- =============================================================

CREATE TABLE restaurant_orders (
    id              BIGSERIAL       PRIMARY KEY,
    location_id     BIGINT          NOT NULL,
    mode            VARCHAR(20)     NOT NULL,           -- pickup | delivery
    customer_name   VARCHAR(255)    NOT NULL,
    customer_phone  VARCHAR(255)    NOT NULL,
    address         VARCHAR(500)    NULL,               -- solo para delivery
    payment_method  VARCHAR(30)     NOT NULL,            -- efectivo | tarjeta | transferencia
    allergies       TEXT            NULL,
    notes           TEXT            NULL,
    subtotal        DECIMAL(10, 2)  NOT NULL,            -- suma de items, sin costo de envío
    status          VARCHAR(30)     NOT NULL DEFAULT 'received',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_restaurant_orders_location
        FOREIGN KEY (location_id) REFERENCES restaurant_locations(id) ON DELETE CASCADE
);

CREATE TABLE restaurant_order_items (
    id              BIGSERIAL       PRIMARY KEY,
    order_id        BIGINT          NOT NULL,
    menu_item_id    BIGINT          NULL,                -- puede quedar NULL si el platillo se borra después
    item_name       VARCHAR(255)    NOT NULL,             -- snapshot: el nombre al momento del pedido
    unit_price      DECIMAL(8, 2)   NOT NULL,             -- snapshot: el precio al momento del pedido
    quantity        INTEGER         NOT NULL,

    CONSTRAINT fk_restaurant_order_items_order
        FOREIGN KEY (order_id) REFERENCES restaurant_orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_restaurant_order_items_menu_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE SET NULL
);

CREATE INDEX idx_restaurant_orders_location_id ON restaurant_orders(location_id);
CREATE INDEX idx_restaurant_orders_created_at ON restaurant_orders(created_at);
CREATE INDEX idx_restaurant_order_items_order_id ON restaurant_order_items(order_id);
CREATE INDEX idx_restaurant_order_items_menu_item_id ON restaurant_order_items(menu_item_id);
