-- ============================================================
-- InsightForge MySQL 种子数据（幂等：仅当不存在时插入，重启不清空业务数据）
-- ============================================================

INSERT IGNORE INTO product (id, name, sku, cost_price) VALUES
(1, 'iPhone 16 秒杀款', 'SKU-IP16', 5999.00),
(2, 'AirPods Pro 3', 'SKU-APP3', 1499.00);

-- 种子活动置为 LIVE（演示「正在热卖」的秒杀场）
INSERT IGNORE INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at) VALUES
(1, '双11 美妆数码场', 'LIVE', 5000000.00, 1.80, '2026-11-11 00:00:00', '2026-11-11 23:59:59'),
(2, '618 数码狂欢节', 'LIVE', 2000000.00, 1.50, '2026-06-18 00:00:00', '2026-06-18 23:59:59');

INSERT IGNORE INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) VALUES
(1, 1, 1, 6299.00, 100, 100, 0, 0),
(2, 2, 2, 1399.00, 50,  50,  0, 0);

-- Phase 14.3：密码为 BCrypt 哈希（admin123 / 123456），不再明文存储
INSERT IGNORE INTO users (id, username, password, role, display_name) VALUES
(1, 'admin',  '$2a$10$5QoKofP4k.xqYBmIVqvMSOhBnh1XPNMPVxq1UMqe2fWeRs3VCC6X.', 'merchant', '运营管理员'),
(2, 'user1',  '$2a$10$Q/1GuYq2bi22.yXIN9o0d.cqD64Lbt2/f492dBsU24UFRI8Siywaa', 'user',     '小明'),
(3, 'user2',  '$2a$10$Q/1GuYq2bi22.yXIN9o0d.cqD64Lbt2/f492dBsU24UFRI8Siywaa', 'user',     '小红');
