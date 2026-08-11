-- ============================================================
-- Phase 19g：咨询工单（转人工客服 + 商家回复窗口）
--  - support_ticket：平台客服答不了 → 买家发起咨询 → 商家端回复
--  - 状态：OPEN（待商家回复）→ REPLIED（已回复，买家可继续追问/关闭）→ CLOSED
--  - 买家/商家都只能看到自己的工单（用户级/商家级隔离）
-- ============================================================

CREATE TABLE support_ticket (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    question    TEXT,
    answer      TEXT,
    status      VARCHAR(16),
    created_at  DATETIME,
    replied_at  DATETIME
);

CREATE INDEX idx_support_user ON support_ticket (user_id, id);
CREATE INDEX idx_support_merchant ON support_ticket (merchant_id, status);
