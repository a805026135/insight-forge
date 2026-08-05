-- ============================================================
-- InsightForge 建表脚本（Phase 1）
-- 同时兼容 H2(MODE=MySQL, 本地零依赖) 与 MySQL 8（迁移后默认 profile）。
-- 仅用标准 SQL（BIGINT / VARCHAR / DECIMAL / DATETIME / INT），
-- 不使用 MySQL 专有子句（如 ENGINE=InnoDB），保证 H2 也能执行。
-- ============================================================

CREATE TABLE IF NOT EXISTS product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128),
    sku         VARCHAR(128),
    cost_price  DECIMAL(12, 2)
);

CREATE TABLE IF NOT EXISTS activity (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128),
    status      VARCHAR(32),
    target_gmv  DECIMAL(14, 2),
    target_roi  DECIMAL(8, 4),
    start_at    DATETIME,
    end_at      DATETIME
);

CREATE TABLE IF NOT EXISTS activity_sku (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id  BIGINT,
    sku_id       BIGINT,
    seckill_price DECIMAL(12, 2),
    total_stock  INT,
    stock        INT,
    sold         INT,
    version      INT
);

CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     VARCHAR(64),
    user_id      BIGINT,
    activity_id  BIGINT,
    sku_id       BIGINT,
    qty          INT,
    status       VARCHAR(32),
    created_at   DATETIME
);
