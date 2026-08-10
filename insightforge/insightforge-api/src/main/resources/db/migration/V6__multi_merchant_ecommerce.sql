-- ============================================================
-- Phase 17/18：多商家电商平台数据模型
--  - merchant：商家主体（店铺名/简介/logo/认证）
--  - product  扩展：merchant_id（归属商家）+ category（分类）+ description + status（ON_SALE/OFF_SHELF）
--  - activity_sku 扩展：merchant_id（同一大促多个商家参与）
--  - user_address：收货地址 CRUD
--  - cart_item：购物车（用户 x 活动SKU x 数量）
--  - orders 扩展：total_amount + 收货快照 + shipped_at（多商品订单）
--  - order_item：订单明细（一单多件）
-- ============================================================

CREATE TABLE merchant (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT,
    name         VARCHAR(128),
    logo         VARCHAR(255),
    description  VARCHAR(512),
    rating       DECIMAL(3, 2) DEFAULT 5.00,
    sales_count  BIGINT       DEFAULT 0,
    verified     BOOLEAN      DEFAULT TRUE,
    created_at   DATETIME
);

-- H2 不支持一条 ALTER 加多列，逐列拆分（MySQL 同样兼容）
ALTER TABLE product ADD COLUMN merchant_id BIGINT NULL;
ALTER TABLE product ADD COLUMN category    VARCHAR(32)  NULL;
ALTER TABLE product ADD COLUMN description VARCHAR(512) NULL;
ALTER TABLE product ADD COLUMN status      VARCHAR(16)  DEFAULT 'ON_SALE';

ALTER TABLE activity_sku ADD COLUMN merchant_id BIGINT NULL;

CREATE TABLE user_address (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT,
    receiver     VARCHAR(64),
    phone        VARCHAR(32),
    province     VARCHAR(64),
    city         VARCHAR(64),
    district     VARCHAR(64),
    detail       VARCHAR(256),
    is_default   BOOLEAN DEFAULT FALSE,
    created_at   DATETIME
);

CREATE TABLE cart_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT,
    activity_sku_id BIGINT,
    qty             INT,
    created_at      DATETIME,
    CONSTRAINT uk_cart_user_sku UNIQUE (user_id, activity_sku_id)
);

ALTER TABLE orders ADD COLUMN total_amount    DECIMAL(14, 2) NULL;
ALTER TABLE orders ADD COLUMN receiver        VARCHAR(64)    NULL;
ALTER TABLE orders ADD COLUMN phone           VARCHAR(32)    NULL;
ALTER TABLE orders ADD COLUMN address_snapshot VARCHAR(512)  NULL;
ALTER TABLE orders ADD COLUMN shipped_at      DATETIME       NULL;

CREATE TABLE order_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        VARCHAR(64),
    activity_sku_id BIGINT,
    product_id      BIGINT,
    product_name    VARCHAR(128),
    product_image   VARCHAR(255),
    merchant_id     BIGINT,
    merchant_name   VARCHAR(128),
    qty             INT,
    price           DECIMAL(12, 2),
    created_at      DATETIME
);

CREATE INDEX idx_product_merchant   ON product (merchant_id);
CREATE INDEX idx_product_category   ON product (category);
CREATE INDEX idx_activity_sku_merchant ON activity_sku (merchant_id);
CREATE INDEX idx_cart_user          ON cart_item (user_id);
CREATE INDEX idx_order_item_order   ON order_item (order_id);
