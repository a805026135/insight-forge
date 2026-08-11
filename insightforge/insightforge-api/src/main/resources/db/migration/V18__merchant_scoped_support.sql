-- ============================================================
-- Phase 19n：商家级客服 Agent 闭环
--  - buyer_chat_message.merchant_id：null=平台客服会话；非空=该商家的专属客服会话
--  - support_ticket.transcript：转人工时截取的对话摘要，商家可看到完整上下文
-- ============================================================

ALTER TABLE buyer_chat_message ADD COLUMN merchant_id BIGINT NULL;
ALTER TABLE support_ticket ADD COLUMN transcript TEXT NULL;

CREATE INDEX idx_buyer_chat_user_merchant ON buyer_chat_message (user_id, merchant_id, id);
