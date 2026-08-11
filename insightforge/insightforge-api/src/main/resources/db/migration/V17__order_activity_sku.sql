-- ============================================================
-- Phase 19m：orders 新增 activity_sku_id，统一 sku_id 语义
--  - 秒杀单：sku_id = product.id（与普通购物车单一致），activity_sku_id = 活动 SKU 行
--  - 修复「秒杀订单在订单页显示未知商品」：订单列表按 product.id 反查商品名/图
-- ============================================================

ALTER TABLE orders ADD COLUMN activity_sku_id BIGINT NULL;

-- 历史秒杀单迁移：用 activity_sku 反查 product.id 写回 sku_id
UPDATE orders o
SET o.activity_sku_id = o.sku_id,
    o.sku_id = (SELECT a.sku_id FROM activity_sku a WHERE a.id = o.sku_id)
WHERE o.activity_id IS NOT NULL
  AND o.activity_sku_id IS NULL
  AND EXISTS (SELECT 1 FROM activity_sku a WHERE a.id = o.sku_id);
