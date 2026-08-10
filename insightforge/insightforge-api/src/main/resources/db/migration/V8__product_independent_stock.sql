-- ============================================================
-- Phase 18：商品独立售卖
--  - product 增加独立库存 stock（普通购买按市场价、扣普通库存）
--  - activity_sku 仍是秒杀库存池（活动期间按秒杀价、扣活动库存）
--  - 种子商品回填库存（默认 500，与实际售卖场景一致）
-- ============================================================

ALTER TABLE product ADD COLUMN stock INT DEFAULT 500 NOT NULL;

UPDATE product SET stock = 500 WHERE stock IS NULL OR stock <= 0;
