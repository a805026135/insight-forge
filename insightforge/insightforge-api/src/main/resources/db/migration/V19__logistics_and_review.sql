-- ============================================================
-- Phase 19o：物流轨迹 + 确认收货 + 商品评价/评分闭环
--  - shipment / shipment_event：商家发货后生成运单与轨迹事件
--  - product_review：订单完成后买家可评价（订单+商品 唯一），回写商品/店铺评分
--  - product 增加评分聚合；merchant 增加评分聚合（V6 已有 rating 初始值）
-- ============================================================

CREATE TABLE shipment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id      VARCHAR(64) UNIQUE,
    tracking_no   VARCHAR(64),
    carrier       VARCHAR(64),
    shipped_at    DATETIME,
    delivered_at  DATETIME
);

CREATE TABLE shipment_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id BIGINT,
    event_time  DATETIME,
    status      VARCHAR(32),
    description VARCHAR(255)
);

CREATE TABLE product_review (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   VARCHAR(64) NOT NULL,
    user_id    BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    merchant_id BIGINT,
    rating     INT,
    content    VARCHAR(1000),
    created_at DATETIME,
    CONSTRAINT uk_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX idx_review_product ON product_review (product_id, id);

ALTER TABLE product ADD COLUMN rating_count INT DEFAULT 0;
ALTER TABLE product ADD COLUMN rating_total INT DEFAULT 0;
ALTER TABLE merchant ADD COLUMN rating_count INT DEFAULT 0;
ALTER TABLE merchant ADD COLUMN rating_total INT DEFAULT 0;
