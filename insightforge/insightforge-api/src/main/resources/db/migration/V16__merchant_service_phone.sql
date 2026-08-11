-- ============================================================
-- Phase 19j：商家公开信息（客服电话等）
--  - merchant.service_phone：店铺客服电话，公开知识（买家客服可检索）
--  - 各商家种子电话（演示数据）
-- ============================================================

ALTER TABLE merchant ADD COLUMN service_phone VARCHAR(32);

UPDATE merchant SET service_phone = '400-600-0001' WHERE id = 1;
UPDATE merchant SET service_phone = '400-100-5678' WHERE id = 2;
UPDATE merchant SET service_phone = '400-200-5678' WHERE id = 3;
UPDATE merchant SET service_phone = '400-300-5678' WHERE id = 4;
UPDATE merchant SET service_phone = '400-400-5678' WHERE id = 5;
UPDATE merchant SET service_phone = '400-500-5678' WHERE id = 6;
