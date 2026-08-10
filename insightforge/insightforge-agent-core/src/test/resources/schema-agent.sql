-- ============================================================
-- Agent Core 测试的本地 schema（H2 MODE=MySQL，零外部依赖）
-- 仅测试用：基础 4 表（供 historical_sales_analyzer 读历史）+ agent 2 表（监测落库）。
-- 与 api/src/main/resources/schema.sql 保持表结构一致，差异只在「这是测试副本」。
-- ============================================================

CREATE TABLE IF NOT EXISTS product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128),
    sku         VARCHAR(128),
    cost_price  DECIMAL(12, 2)
);

CREATE TABLE IF NOT EXISTS activity (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128),
    status      VARCHAR(32),
    target_gmv  DECIMAL(14, 2),
    target_roi  DECIMAL(8, 4),
    start_at    DATETIME,
    end_at      DATETIME
);

CREATE TABLE IF NOT EXISTS activity_sku (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id  BIGINT,
    sku_id       BIGINT,
    seckill_price DECIMAL(12, 2),
    total_stock  INT,
    stock        INT,
    sold         INT,
    version      INT
);

CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     VARCHAR(64) UNIQUE,
    user_id      BIGINT,
    activity_id  BIGINT,
    sku_id       BIGINT,
    qty          INT,
    price        DECIMAL(12, 2),
    status       VARCHAR(32),
    created_at   DATETIME
);

CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id  BIGINT,
    sku_id       BIGINT,
    action_type  VARCHAR(32),
    amount       INT,
    detail       VARCHAR(512),
    created_at   DATETIME
);

CREATE TABLE IF NOT EXISTS agent_trace (
    trace_id         VARCHAR(64)  PRIMARY KEY,
    activity_id      BIGINT,
    started_at       DATETIME,
    finished_at      DATETIME,
    status           VARCHAR(16),
    failure_kind     VARCHAR(16),
    model_version    VARCHAR(64),
    prompt_version   VARCHAR(64),
    budget_tokens    INT,
    budget_cost_usd  DECIMAL(10,4),
    total_latency_ms BIGINT,
    correct_failure  BOOLEAN,
    review_status    VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS agent_span (
    span_id        VARCHAR(64) PRIMARY KEY,
    trace_id       VARCHAR(64) NOT NULL,
    parent_span_id VARCHAR(64),
    role           VARCHAR(32),
    step_name      VARCHAR(64),
    tool_name      VARCHAR(64),
    tool_input     VARCHAR(4000),
    tool_output    VARCHAR(4000),
    token_in       INT,
    token_out      INT,
    latency_ms     BIGINT,
    retry_count    INT,
    status         VARCHAR(16),
    confidence     DECIMAL(3,2),
    artifact_ref   VARCHAR(128)
);

-- Phase 8：护栏策略版本化（测试副本）
CREATE TABLE IF NOT EXISTS guardrail_policy (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id  BIGINT,
    version      VARCHAR(32),
    policy_json  VARCHAR(8000),
    created_at   DATETIME,
    UNIQUE (activity_id, version)
);
