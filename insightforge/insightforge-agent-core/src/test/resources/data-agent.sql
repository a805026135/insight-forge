-- ============================================================
-- Agent Core 测试种子数据（Phase 7）
-- 提供一件商品 + 一个活动 + 一个 SKU，并预置若干历史订单，
-- 供 historical_sales_analyzer 工具真实查库、算出「已售/售罄率/GMV」。
-- ============================================================

INSERT INTO product (id, name, sku, cost_price) VALUES (1, 'iPhone 16 秒杀款', 'SKU-IP16', 5999.00);

INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at)
VALUES (1, '双11 美妆数码场', 'DRAFT', 5000000.00, 1.80, '2026-11-11 00:00:00', '2026-11-11 23:59:59');

-- skuId=1 对应 product.id=1；初始库存 100，已售 42（下面订单累计），剩余 58
INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version)
VALUES (1, 1, 1, 6299.00, 100, 58, 42, 0);

-- 历史订单：user 1001~1005 各买若干，累计 qty=42（与上面 sold 对齐，便于断言）
INSERT INTO orders (order_id, user_id, activity_id, sku_id, qty, status, created_at)
VALUES
    ('OF-1-1-1001', 1001, 1, 1, 10, 'DONE', '2026-11-11 00:05:00'),
    ('OF-1-1-1002', 1002, 1, 1, 10, 'DONE', '2026-11-11 00:07:00'),
    ('OF-1-1-1003', 1003, 1, 1, 10, 'DONE', '2026-11-11 00:09:00'),
    ('OF-1-1-1004', 1004, 1, 1,  8, 'DONE', '2026-11-11 00:12:00'),
    ('OF-1-1-1005', 1005, 1, 1,  4, 'DONE', '2026-11-11 00:15:00');
