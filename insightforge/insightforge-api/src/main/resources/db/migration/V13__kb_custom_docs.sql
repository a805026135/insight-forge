-- ============================================================
-- Phase 19e：商家自定义知识（上传自己的文档进 RAG 知识库）
--  - agent_kb_custom_doc：原始文档（标题/全文），doc_key 关联向量库
--  - merchant_id 强制商家隔离；支持删除（同时清向量库与切块）
-- ============================================================

CREATE TABLE agent_kb_custom_doc (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    doc_key     VARCHAR(128) NOT NULL,
    title       VARCHAR(255),
    content     TEXT,
    created_at  DATETIME
);

CREATE INDEX idx_agent_kb_custom_merchant ON agent_kb_custom_doc (merchant_id);
CREATE INDEX idx_agent_kb_custom_doc ON agent_kb_custom_doc (doc_key);
