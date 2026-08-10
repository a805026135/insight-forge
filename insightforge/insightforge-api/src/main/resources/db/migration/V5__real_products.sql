-- ============================================================
-- Phase 16：真实商品库 + 商品图片（Flyway 单一迁移源，MySQL/H2 共用）
--  - product 增加 market_price（市场价/原价，供买家页展示）与 image_url（前端静态图）
--  - 把种子商品升级为真实在售数码/家电/音频商品，并补充 12 款商品
--  - 为两个 LIVE 活动各上架 6 个 SKU
-- ============================================================

ALTER TABLE product ADD COLUMN market_price DECIMAL(12, 2) NULL;
ALTER TABLE product ADD COLUMN image_url      VARCHAR(255)  NULL;

UPDATE product SET
    name        = 'iPhone 16 Pro 256GB 原色钛金属',
    sku         = 'SKU-IP16PRO',
    cost_price  = 8300.00,
    market_price = 8999.00,
    image_url   = '/products/iphone16pro.jpg'
WHERE id = 1;

UPDATE product SET
    name        = 'AirPods Pro 3 主动降噪耳机',
    sku         = 'SKU-APP3',
    cost_price  = 1600.00,
    market_price = 1899.00,
    image_url   = '/products/airpodspro3.jpg'
WHERE id = 2;

INSERT INTO product (id, name, sku, cost_price, market_price, image_url) VALUES
(3,  'MacBook Air 13 英寸 M4 16G+512G',    'SKU-MBA13',    7200.00, 7999.00, '/products/macbookair13.jpg'),
(4,  'iPad mini 7 256GB 星光色',           'SKU-IPDM7',    3600.00, 3999.00, '/products/ipadmini7.jpg'),
(5,  'Apple Watch Series 11 42mm',         'SKU-AW11',     2700.00, 2999.00, '/products/applewatch11.jpg'),
(6,  '华为 Mate 70 Pro 12G+512G',          'SKU-MATE70P',  5900.00, 6499.00, '/products/mate70pro.jpg'),
(7,  '小米 15 16G+512G 白色',              'SKU-MI15',     4100.00, 4499.00, '/products/mi15.jpg'),
(8,  '小米手环 9 Pro',                     'SKU-MIB9P',    340.00,  399.00,  '/products/miband9pro.jpg'),
(9,  '索尼 WH-1000XM6 头戴降噪耳机',        'SKU-SONY1000X6', 2500.00, 2899.00, '/products/sonywh1000xm6.jpg'),
(10, '大疆 DJI Mini 5 Pro 畅飞套装',        'SKU-DJIMINI5', 5400.00, 5988.00, '/products/djimini5pro.jpg'),
(11, '罗技 MX Master 3S 无线鼠标',          'SKU-MX3S',     600.00,  699.00,  '/products/mxmaster3s.jpg'),
(12, '戴森 HD16 新一代吹风机',              'SKU-DYSONHD16', 2800.00, 3199.00, '/products/dysonhd16.jpg');

-- 双 11 数码新品场（活动 1）上架 6 款：Apple 全家桶 + 戴森
UPDATE activity_sku SET seckill_price = 8599.00 WHERE id = 1; -- iPhone 16 Pro
UPDATE activity_sku SET seckill_price = 1699.00 WHERE id = 2; -- AirPods Pro 3
INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) VALUES
(3,  1, 3,  7499.00, 50,  50,  0, 0),
(4,  1, 4,  3699.00, 80,  80,  0, 0),
(5,  1, 5,  2799.00, 60,  60,  0, 0),
(6,  1, 12, 2999.00, 50,  50,  0, 0);

-- 618 国潮数码狂欢（活动 2）上架 6 款：华为 / 小米 / 音频 / 数码配件
INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) VALUES
(7,  2, 6,  5999.00, 100, 100, 0, 0),
(8,  2, 7,  4199.00, 120, 120, 0, 0),
(9,  2, 8,  369.00,  200, 200, 0, 0),
(10, 2, 9,  2699.00, 60,  60,  0, 0),
(11, 2, 10, 5499.00, 40,  40,  0, 0),
(12, 2, 11, 649.00,  150, 150, 0, 0);
