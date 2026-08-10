-- ============================================================
-- Phase 18：购物车改存商品（product_id），不再强绑活动 SKU
--  - 保留 activity_sku_id 列（兼容旧数据/秒杀快捷入口），新增 product_id
-- ============================================================

ALTER TABLE cart_item ADD COLUMN product_id BIGINT NULL;

UPDATE cart_item
SET product_id = (SELECT sku_id FROM activity_sku WHERE id = cart_item.activity_sku_id)
WHERE product_id IS NULL AND activity_sku_id IS NOT NULL;

-- H2/MySQL 兼容：先改类型，再设为非空（H2 不支持 MODIFY COLUMN）
ALTER TABLE cart_item ALTER COLUMN product_id BIGINT;
ALTER TABLE cart_item ALTER COLUMN product_id SET NOT NULL;
