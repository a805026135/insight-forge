-- ============================================================
-- Phase 19f：买家客服历史持久化 + 真实退款/售后工单
--  - buyer_chat_message：客服问答历史（用户级隔离，跨会话可见）
--  - after_sale：退款工单（APPLIED 待审核 → APPROVED 已通过 → REFUNDED 已退款
--                 / REJECTED 已驳回；用户可取消 APPLIED 工单）
-- ============================================================

CREATE TABLE buyer_chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    role       VARCHAR(16),
    content    TEXT,
    created_at DATETIME
);

CREATE INDEX idx_buyer_chat_user ON buyer_chat_message (user_id, id);

CREATE TABLE after_sale (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    order_id     VARCHAR(64),
    sku_id       BIGINT,
    product_name VARCHAR(128),
    qty          INT,
    amount       DECIMAL(12, 2),
    reason       VARCHAR(255),
    status       VARCHAR(32),
    created_at   DATETIME,
    updated_at   DATETIME
);

CREATE INDEX idx_after_sale_user ON after_sale (user_id, id);
