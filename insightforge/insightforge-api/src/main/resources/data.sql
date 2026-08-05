-- ============================================================
-- InsightForge 种子数据（Phase 1 演示用）
-- 每次应用启动（spring.sql.init.mode=always）都会先清空 4 张表再插入，
-- 方便反复重启看「库存 100 → 抢购 → 超卖」的效果。
-- ============================================================

DELETE FROM orders;
DELETE FROM activity_sku;
DELETE FROM activity;
DELETE FROM product;

INSERT INTO product (id, name, sku, cost_price) VALUES (1, 'iPhone 16 秒杀款', 'SKU-IP16', 5999.00);

INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at)
VALUES (1, '双11 美妆数码场', 'DRAFT', 5000000.00, 1.80, '2026-11-11 00:00:00', '2026-11-11 23:59:59');

-- skuId=1 对应 product.id=1；初始/剩余库存均 100；sold=0；version=0（Phase 2 乐观锁用）
INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version)
VALUES (1, 1, 1, 6299.00, 100, 100, 0, 0);
