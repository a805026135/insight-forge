-- ============================================================
-- Phase 19c：RAG 真实种子数据
--  - activity / activity_sku：几家商家各 1 个真实感的秒杀活动
--  - audit_log：补真实感的商家历史决策（决策知识库的数据源）
--  - product.description：补全卖点，让商品知识库更真实
-- 显式 id 用 9001+，避免与既有种子/自增冲突（H2/MySQL 通用）
-- ============================================================

INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at) VALUES
(9001, '华为 Mate 70 系列限时秒杀', 'ONGOING', 1200000.00, 2.10, '2026-08-15 10:00:00', '2026-08-18 23:59:59'),
(9002, '小米 15 系列新品抢先购',   'ONGOING',  900000.00, 1.90, '2026-08-16 10:00:00', '2026-08-20 23:59:59'),
(9003, '数码影音年中大促',         'ONGOING', 1500000.00, 2.30, '2026-08-18 10:00:00', '2026-08-25 23:59:59');

INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version, merchant_id) VALUES
(9001, 9001, 6,  5799.00, 120,  96,  24, 0, 2),
(9002, 9001, 23, 4599.00,  80,  68,  12, 0, 2),
(9003, 9002, 24, 4699.00, 150, 121,  29, 0, 3),
(9004, 9002, 25, 3299.00, 200, 173,  27, 0, 3),
(9005, 9003, 14, 2399.00, 100,  82,  18, 0, 4),
(9006, 9003, 16, 5299.00,  90,  71,  19, 0, 5);

INSERT INTO audit_log (id, activity_id, sku_id, action_type, amount, detail, created_at) VALUES
(9001, 0, 23, 'REPRICE',     0,  '商家#2 批准执行：市场价调整为 5199.00',                    '2026-08-05 14:20:00'),
(9002, 0, 6,  'ADD_STOCK',   50, '商家#2 批准执行：增加库存 50 件，当前库存 540',             '2026-08-08 09:15:00'),
(9003, 0, 24, 'ADD_STOCK',   80, '商家#3 批准执行：增加库存 80 件，当前库存 500',             '2026-08-09 11:00:00'),
(9004, 0, 14, 'TOGGLE_SHELF',0,  '商家#4 批准执行：上架成功',                                 '2026-08-10 16:40:00'),
(9005, 0, 16, 'REPRICE',     0,  '商家#5 批准执行：市场价调整为 5988.00',                    '2026-08-11 10:05:00');

UPDATE product SET description = '昆仑玻璃，卫星通信，超可靠玄武架构，影像大师级体验' WHERE id = 6;
UPDATE product SET description = '柔性 OLED 屏，PC 级体验，鸿蒙多屏协同' WHERE id = 23;
UPDATE product SET description = '骁龙 8 至尊版，徕卡光学影像，2K 超清屏' WHERE id = 24;
UPDATE product SET description = '2K 护眼屏，骁龙 8 至尊版，大电池长续航' WHERE id = 25;
UPDATE product SET description = 'LDAC 无损音质，双芯降噪，头戴舒适' WHERE id = 14;
UPDATE product SET description = '4K120fps 航拍，超强防抖，轻至 249g' WHERE id = 16;
