-- ============================================================
-- Phase 17 种子数据：6 家商家 + 4 个商家账号 + 40 款商品目录
--  - 既有 12 款商品归属到商家（UPDATE）
--  - 新增 28 款商品补齐各商家货架（INSERT，id 13-40）
--  - activity_sku 回填 merchant_id
--  - 商家账号统一密码 123456（BCrypt）
-- ============================================================

-- ---------- 商家主体 ----------
INSERT INTO merchant (id, user_id, name, logo, description, rating, sales_count, verified, created_at) VALUES
(1, 1, 'Apple 官方旗舰店', '', 'Apple 官方渠道，正品保证，全国联保', 4.98, 128500, TRUE, '2025-01-01 00:00:00'),
(2, 4, '华为官方旗舰店', '', '华为终端官方旗舰店，鸿蒙生态全家桶', 4.96, 98600, TRUE, '2025-02-01 00:00:00'),
(3, 5, '小米官方旗舰店', '', '小米官方直营，性价比之选', 4.92, 152300, TRUE, '2025-03-01 00:00:00'),
(4, 6, '索尼官方旗舰店', '', '索尼影音/相机/游戏官方店', 4.95, 67400, TRUE, '2025-04-01 00:00:00'),
(5, 7, 'DJI 大疆官方旗舰店', '', '大疆创新官方店，航拍旗舰', 4.97, 45300, TRUE, '2025-05-01 00:00:00'),
(6, 8, '戴森官方旗舰店', '', '戴森科技官方店，黑科技家电', 4.90, 38900, TRUE, '2025-06-01 00:00:00');

-- ---------- 商家账号（role=merchant，密码均为 123456） ----------
INSERT INTO users (id, username, password, role, display_name) VALUES
(4, 'huawei',  '$2a$10$Q/1GuYq2bi22.yXIN9o0d.cqD64Lbt2/f492dBsU24UFRI8Siywaa', 'merchant', '华为官方'),
(5, 'xiaomi',  '$2a$10$Q/1GuYq2bi22.yXIN9o0d.cqD64Lbt2/f492dBsU24UFRI8Siywaa', 'merchant', '小米官方'),
(6, 'sony',    '$2a$10$Q/1GuYq2bi22.yXIN9o0d.cqD64Lbt2/f492dBsU24UFRI8Siywaa', 'merchant', '索尼官方'),
(7, 'dji',     '$2a$10$Q/1GuYq2bi22.yXIN9o0d.cqD64Lbt2/f492dBsU24UFRI8Siywaa', 'merchant', '大疆官方'),
(8, 'dyson',   '$2a$10$Q/1GuYq2bi22.yXIN9o0d.cqD64Lbt2/f492dBsU24UFRI8Siywaa', 'merchant', '戴森官方');

-- ---------- 既有 12 款商品归属商家 + 分类 ----------
UPDATE product SET merchant_id = 1, category = '手机数码', status = 'ON_SALE' WHERE id IN (1, 3, 4, 5);
UPDATE product SET merchant_id = 1, category = '耳机音频', status = 'ON_SALE' WHERE id = 2;
UPDATE product SET merchant_id = 2, category = '手机数码', status = 'ON_SALE' WHERE id = 6;
UPDATE product SET merchant_id = 3, category = '手机数码', status = 'ON_SALE' WHERE id = 7;
UPDATE product SET merchant_id = 3, category = '智能穿戴', status = 'ON_SALE' WHERE id = 8;
UPDATE product SET merchant_id = 4, category = '耳机音频', status = 'ON_SALE' WHERE id = 9;
UPDATE product SET merchant_id = 5, category = '无人机航拍', status = 'ON_SALE' WHERE id = 10;
UPDATE product SET merchant_id = 4, category = '电脑外设', status = 'ON_SALE' WHERE id = 11;
UPDATE product SET merchant_id = 6, category = '个护家电', status = 'ON_SALE' WHERE id = 12;

-- ---------- 新增 28 款商品（Apple 6 / 华为 6 / 小米 6 / 索尼 5 / 大疆 5 / 戴森 4 中挑 28） ----------
INSERT INTO product (id, name, sku, cost_price, market_price, image_url, merchant_id, category, description, status) VALUES
(13, 'MacBook Pro 14 英寸 M4 Pro 24G+1T', 'SKU-MBP14', 15800.00, 17499.00, '/products/macbookpro14.jpg', 1, '笔记本电脑', 'M4 Pro 芯片，专业创作利器', 'ON_SALE'),
(14, 'iPad Air 11 英寸 M3', 'SKU-IPA11', 3900.00, 4399.00, '/products/ipadair11.jpg', 1, '平板电脑', '轻薄全能，M3 芯片', 'ON_SALE'),
(15, 'Apple Watch SE 3 40mm', 'SKU-AWSE3', 1700.00, 1999.00, '/products/applewatchse3.jpg', 1, '智能穿戴', '入门款智能手表', 'ON_SALE'),
(16, 'AirPods 4 主动降噪版', 'SKU-AP4', 1100.00, 1399.00, '/products/airpods4.jpg', 1, '耳机音频', '自适应降噪，全天舒适', 'ON_SALE'),
(17, 'iMac 24 英寸 M4 一体机', 'SKU-IMAC24', 9200.00, 9999.00, '/products/imac24.jpg', 1, '电脑办公', '一体成型，色彩出众', 'ON_SALE'),
(18, '华为 Mate 70 12G+512G', 'SKU-MATE70', 4700.00, 5499.00, '/products/mate70.jpg', 2, '手机数码', '鸿蒙 4，卫星通信', 'ON_SALE'),
(19, '华为 Pura 70 Pro 12G+512G', 'SKU-PURA70P', 5200.00, 6199.00, '/products/pura70pro.jpg', 2, '手机数码', '影像旗舰，风驰闪拍', 'ON_SALE'),
(20, '华为 MateBook X Pro 轻薄本', 'SKU-MBXPRO', 9300.00, 10999.00, '/products/matebookxpro.jpg', 2, '笔记本电脑', '3.1K 触控屏，超轻便携', 'ON_SALE'),
(21, '华为 FreeBuds Pro 4', 'SKU-FBP4', 1100.00, 1399.00, '/products/freebudspro4.jpg', 2, '耳机音频', '星闪连接，无损音质', 'ON_SALE'),
(22, '华为 Watch GT 5 46mm', 'SKU-WGT5', 1300.00, 1588.00, '/products/watchgt5.jpg', 2, '智能穿戴', '玄玑感知系统，两周续航', 'ON_SALE'),
(23, '华为 MatePad Pro 13.2', 'SKU-MPP132', 4600.00, 5199.00, '/products/matepadpro.jpg', 2, '平板电脑', '柔性 OLED 屏，PC 级体验', 'ON_SALE'),
(24, '小米 15 Pro 16G+512G', 'SKU-MI15P', 4600.00, 5299.00, '/products/mi15pro.jpg', 3, '手机数码', '骁龙 8 至尊版，徕卡影像', 'ON_SALE'),
(25, 'Redmi K80 Pro 12G+256G', 'SKU-K80P', 3000.00, 3699.00, '/products/redmik80.jpg', 3, '手机数码', '旗舰性能，2K 直屏', 'ON_SALE'),
(26, '小米平板 7 Pro 12.4', 'SKU-MIPAD7P', 2400.00, 2999.00, '/products/mipad7.jpg', 3, '平板电脑', '3.2K 高刷屏，办公娱乐两不误', 'ON_SALE'),
(27, '小米 Buds 5 Pro', 'SKU-MIB5P', 850.00, 1099.00, '/products/mibuds5.jpg', 3, '耳机音频', '圈瓷同轴，55dB 降噪', 'ON_SALE'),
(28, '小米 Watch S4 46mm', 'SKU-MIWS4', 900.00, 1199.00, '/products/miwatchs4.jpg', 3, '智能穿戴', 'eSIM 独立通信，澎湃 OS', 'ON_SALE'),
(29, '米家扫拖机器人 X40', 'SKU-MJSWEEP', 2500.00, 3299.00, '/products/mijia_sweep.jpg', 3, '个护家电', '双机械臂，AI 避障', 'ON_SALE'),
(30, 'PlayStation 5 Pro 游戏主机', 'SKU-PS5PRO', 4600.00, 5599.00, '/products/sony_ps5.jpg', 4, '游戏影音', '8K 游戏，光追增强', 'ON_SALE'),
(31, '索尼 A7M4 全画幅微单', 'SKU-SONYA7M4', 14500.00, 16999.00, '/products/sony_a7m4.jpg', 4, '摄影器材', '3300 万像素，视频双修', 'ON_SALE'),
(32, '索尼 BRAVIA 65 英寸 XR 电视', 'SKU-SONYTV', 7200.00, 8999.00, '/products/sony_bravia.jpg', 4, '游戏影音', 'XR 认知芯片，明锐动态', 'ON_SALE'),
(33, '索尼 SRS-XB100 便携音箱', 'SKU-SRSXB', 350.00, 469.00, '/products/sony_srs.jpg', 4, '耳机音频', 'IP67 防水，16 小时续航', 'ON_SALE'),
(34, '索尼 WH-1000XM4 头戴降噪耳机', 'SKU-SONY1000X4', 1600.00, 1899.00, '/products/sony_xm4.jpg', 4, '耳机音频', '降噪标杆，30 小时续航', 'ON_SALE'),
(35, '大疆 Air 3S 双摄航拍无人机', 'SKU-DJIAIR3S', 6200.00, 6988.00, '/products/djiair3s.jpg', 5, '无人机航拍', '一英寸主摄，全向避障', 'ON_SALE'),
(36, '大疆 Mavic 4 Pro 旗舰航拍机', 'SKU-DJIM4P', 10500.00, 11988.00, '/products/dji_mavic4.jpg', 5, '无人机航拍', '哈苏 4/3 画幅，8K 视频', 'ON_SALE'),
(37, '大疆 Osmo Pocket 3 云台相机', 'SKU-DJIP3', 3000.00, 3499.00, '/products/dji_pocket3.jpg', 5, '摄影器材', '一英寸传感器，旋转屏', 'ON_SALE'),
(38, '大疆 Osmo Action 5 Pro 运动相机', 'SKU-DJIA5P', 2100.00, 2598.00, '/products/dji_action5.jpg', 5, '摄影器材', '4K120fps，超强防抖', 'ON_SALE'),
(39, '大疆 Mic 2 无线麦克风', 'SKU-DJIMIC2', 1700.00, 1999.00, '/products/dji_mic2.jpg', 5, '摄影器材', '智能降噪，一拖二', 'ON_SALE'),
(40, '戴森 V15 Detect 无绳吸尘器', 'SKU-DYV15', 3700.00, 4590.00, '/products/dyson_v15.jpg', 6, '个护家电', '激光探测，吸入功率 240AW', 'ON_SALE'),
(41, '戴森 AM07 无叶风扇', 'SKU-DYAM07', 1900.00, 2690.00, '/products/dyson_am.jpg', 6, '个护家电', 'Air Multiplier 气流倍增', 'ON_SALE'),
(42, '戴森 PH05 空气净化风扇', 'SKU-DYPH05', 4200.00, 5490.00, '/products/dyson_ph.jpg', 6, '个护家电', '净化+凉风+暖风三合一', 'ON_SALE');

-- ---------- activity_sku 回填 merchant_id ----------
UPDATE activity_sku SET merchant_id = 1 WHERE sku_id IN (1, 2, 3, 4, 5);
UPDATE activity_sku SET merchant_id = 2 WHERE sku_id = 6;
UPDATE activity_sku SET merchant_id = 3 WHERE sku_id IN (7, 8);
UPDATE activity_sku SET merchant_id = 4 WHERE sku_id IN (9, 11);
UPDATE activity_sku SET merchant_id = 5 WHERE sku_id = 10;
UPDATE activity_sku SET merchant_id = 6 WHERE sku_id = 12;

-- ---------- 用户种子收货地址（演示用） ----------
INSERT INTO user_address (id, user_id, receiver, phone, province, city, district, detail, is_default, created_at) VALUES
(1, 2, '小明', '13800138001', '广东省', '深圳市', '南山区', '科技园南路 100 号 3 栋 501', TRUE, '2026-08-01 10:00:00'),
(2, 2, '小明', '13800138001', '北京市', '北京市', '海淀区', '中关村大街 27 号', FALSE, '2026-08-02 11:00:00'),
(3, 3, '小红', '13900139002', '浙江省', '杭州市', '西湖区', '文三路 90 号 2 幢 802', TRUE, '2026-08-03 12:00:00');
