-- ============================================================
-- Phase 19：Agent 知识库（RAG 落库）
--  - agent_kb_document：文档级元数据（doc_key 幂等，重新入库即版本 +1）
--  - agent_kb_chunk：切块内容 + 向量（JSON 数组存 TEXT，兼容 H2/MySQL）
--  - merchant_id NULL = 平台全局知识（所有商家可见），否则仅归属商家可见
--  - dimension 记录向量维度：本地兜底 embedding（512）与
--    DashScope text-embedding-v4（1024）维度不同，加载索引时按维度过滤，
--    避免“维度不一致导致相似度计算错误”
-- ============================================================

CREATE TABLE agent_kb_document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_key     VARCHAR(128) NOT NULL,
    title       VARCHAR(255),
    source_type VARCHAR(32),
    merchant_id BIGINT,
    version     INT DEFAULT 1,
    chunk_count INT DEFAULT 0,
    created_at  DATETIME,
    updated_at  DATETIME
);

CREATE UNIQUE INDEX uk_agent_kb_doc_key ON agent_kb_document (doc_key);

CREATE TABLE agent_kb_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_key     VARCHAR(128) NOT NULL,
    document_id BIGINT,
    merchant_id BIGINT,
    title       VARCHAR(255),
    source_type VARCHAR(32),
    content     TEXT,
    embedding   TEXT,
    dimension   INT,
    created_at  DATETIME
);

CREATE INDEX idx_agent_kb_chunk_merchant ON agent_kb_chunk (merchant_id);
CREATE INDEX idx_agent_kb_chunk_doc ON agent_kb_chunk (doc_key);
