-- ============================================================
-- Phase 19p：售后申诉状态机
--  - after_sale 增加 merchant_id（商家侧按店铺隔离处理退款）
--  - 状态流转：APPLIED → APPROVED → REFUNDING → REFUNDED
--                     └→ REJECTED → APPEAL → 平台/商家复核 → APPROVED / REJECTED
--                     └→ CANCELLED（用户取消）
-- ============================================================

ALTER TABLE after_sale ADD COLUMN merchant_id BIGINT NULL;
ALTER TABLE after_sale ADD COLUMN appeal_reason VARCHAR(255) NULL;
ALTER TABLE after_sale ADD COLUMN appeal_at DATETIME NULL;
ALTER TABLE after_sale ADD COLUMN handled_by VARCHAR(32) NULL;
ALTER TABLE after_sale ADD COLUMN handled_at DATETIME NULL;
ALTER TABLE after_sale ADD COLUMN note VARCHAR(255) NULL;

CREATE INDEX idx_after_sale_merchant ON after_sale (merchant_id, status);
