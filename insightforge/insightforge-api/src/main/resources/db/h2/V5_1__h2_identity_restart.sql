-- H2 专用：V5 显式插入 product id 3-12 / activity_sku id 3-12 后，H2 的自增计数不自动推进，
-- 必须手动 RESTART，否则后续 API 新建商品/活动 SKU 会主键冲突（MySQL 会自动推进，无需此文件）。
ALTER TABLE product      ALTER COLUMN id RESTART WITH 13;
ALTER TABLE activity_sku ALTER COLUMN id RESTART WITH 13;
