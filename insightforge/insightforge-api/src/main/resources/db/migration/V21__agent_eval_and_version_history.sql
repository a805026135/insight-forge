CREATE TABLE agent_prompt_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_key VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE (prompt_key, version)
);

CREATE TABLE agent_kb_document_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_key VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    title VARCHAR(255),
    source_type VARCHAR(32),
    merchant_id BIGINT,
    content MEDIUMTEXT,
    checksum VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE (doc_key, version)
);

CREATE INDEX idx_prompt_version_key ON agent_prompt_version (prompt_key, created_at);
CREATE INDEX idx_kb_version_key ON agent_kb_document_version (doc_key, version);
