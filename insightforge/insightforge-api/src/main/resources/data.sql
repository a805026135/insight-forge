-- ============================================================
-- InsightForge 种子数据（Phase 1 演示用）
-- 每次应用启动（spring.sql.init.mode=always）都会先清空再插入，
-- 方便反复重启看「库存 100 → 抢购 → 超卖」的效果。
-- ============================================================

DELETE FROM orders;
DELETE FROM activity_sku;
DELETE FROM activity;
DELETE FROM product;

-- 产品
INSERT INTO product (id, name, sku, cost_price) VALUES
(1, 'iPhone 16 秒杀款', 'SKU-IP16', 5999.00),
(2, 'AirPods Pro 3', 'SKU-APP3', 1499.00);

-- 活动
-- 种子活动置为 LIVE（演示「正在热卖」的秒杀场）；新建活动仍从 DRAFT 开始走状态机。
INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at) VALUES
(1, '双11 美妆数码场', 'LIVE', 5000000.00, 1.80, '2026-11-11 00:00:00', '2026-11-11 23:59:59'),
(2, '618 数码狂欢节', 'LIVE', 2000000.00, 1.50, '2026-06-18 00:00:00', '2026-06-18 23:59:59');

-- 活动 SKU
INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) VALUES
(1, 1, 1, 6299.00, 100, 100, 0, 0),
(2, 2, 2, 1399.00, 50,  50,  0, 0);

-- H2 显式 ID 插入后序列计数器不会自动推进，须手动同步，否则后续 IdType.AUTO
-- 插入会从 1 开始导致主键冲突
ALTER TABLE product      ALTER COLUMN id RESTART WITH 3;
ALTER TABLE activity     ALTER COLUMN id RESTART WITH 3;
ALTER TABLE activity_sku ALTER COLUMN id RESTART WITH 3;

-- 种子用户
MERGE INTO users (id, username, password, role, display_name) KEY (username)
VALUES (1, 'admin',  'admin123', 'merchant', '运营管理员'),
       (2, 'user1',  '123456',   'user',     '小明'),
       (3, 'user2',  '123456',   'user',     '小红');
